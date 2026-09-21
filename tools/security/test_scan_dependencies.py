#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""`scan_dependencies.py` 的单元测试。

    python3 tools/security/test_scan_dependencies.py

用标准库 unittest，无第三方依赖——与项目「不引新运行时依赖」的纪律一致。
重点是两类断言：
  1. 纯映射逻辑（严重度归一、CVSS 分档、组件筛选）；
  2. **fail-closed 行为**：网络失败必须抛错，绝不产出「0 漏洞」的假报告。
"""
import sys
import unittest
import urllib.error

import scan_dependencies as sd


class NormalizeSeverityTest(unittest.TestCase):

    def test_moderate_maps_to_medium(self):
        self.assertEqual("MEDIUM", sd.normalize_severity("MODERATE"))

    def test_case_and_space_insensitive(self):
        self.assertEqual("HIGH", sd.normalize_severity(" high "))

    def test_none_is_unknown(self):
        self.assertEqual("UNKNOWN", sd.normalize_severity(None))

    def test_unrecognized_is_unknown(self):
        self.assertEqual("UNKNOWN", sd.normalize_severity("banana"))


class CvssScoreTest(unittest.TestCase):

    def test_critical(self):
        self.assertEqual("CRITICAL", sd.cvss_score_to_level("9.8"))

    def test_high_boundary(self):
        self.assertEqual("HIGH", sd.cvss_score_to_level(7.0))

    def test_medium(self):
        self.assertEqual("MEDIUM", sd.cvss_score_to_level(5.5))

    def test_low(self):
        self.assertEqual("LOW", sd.cvss_score_to_level(2.0))

    def test_vector_is_unknown_not_guessed(self):
        self.assertEqual("UNKNOWN", sd.cvss_score_to_level("CVSS:3.1/AV:N/AC:L/PR:N"))

    def test_none_is_unknown(self):
        self.assertEqual("UNKNOWN", sd.cvss_score_to_level(None))


class SeverityOfTest(unittest.TestCase):

    def test_database_specific_wins(self):
        vuln = {"database_specific": {"severity": "CRITICAL"},
                "severity": [{"score": "2.0"}]}
        self.assertEqual("CRITICAL", sd.severity_of(vuln))

    def test_falls_back_to_numeric_cvss(self):
        vuln = {"severity": [{"score": "7.5"}]}
        self.assertEqual("HIGH", sd.severity_of(vuln))

    def test_vector_only_is_unknown(self):
        vuln = {"severity": [{"score": "CVSS:3.1/AV:N/AC:L"}]}
        self.assertEqual("UNKNOWN", sd.severity_of(vuln))

    def test_empty_is_unknown(self):
        self.assertEqual("UNKNOWN", sd.severity_of({}))


class ComponentFilterTest(unittest.TestCase):

    def test_own_group_is_not_third_party(self):
        self.assertFalse(sd.is_third_party(
            {"group": "com.tg.heyisheng.bot", "name": "tgg-core"}))

    def test_tgg_prefix_is_not_third_party(self):
        self.assertFalse(sd.is_third_party({"name": "tgg-admin"}))

    def test_third_party(self):
        self.assertTrue(sd.is_third_party(
            {"group": "org.springframework.boot", "name": "spring-boot"}))

    def test_osv_name_uses_colon(self):
        self.assertEqual("org.telegram:telegrambots-meta",
                         sd.osv_package_name({"group": "org.telegram",
                                              "name": "telegrambots-meta"}))

    def test_osv_name_without_group(self):
        self.assertEqual("standalone", sd.osv_package_name({"name": "standalone"}))

    def test_coordinate_includes_version(self):
        self.assertEqual("org.telegram:telegrambots-meta@10.3.0",
                         sd.coordinate({"group": "org.telegram",
                                        "name": "telegrambots-meta",
                                        "version": "10.3.0"}))


class ExtractComponentsTest(unittest.TestCase):

    def test_drops_own_modules_and_missing_version_and_duplicates(self):
        sbom = {"components": [
            {"group": "com.tg.heyisheng.bot", "name": "tgg-core", "version": "1"},
            {"group": "org.telegram", "name": "telegrambots-meta", "version": "10.3.0"},
            {"group": "org.telegram", "name": "telegrambots-meta", "version": "10.3.0"},
            {"group": "org.telegram", "name": "telegrambots-meta"},
            {"group": "com.mysql", "name": "mysql-connector-j", "version": "9.7.0"},
        ]}
        result = sd.extract_components(sbom)
        self.assertEqual(["org.telegram:telegrambots-meta@10.3.0",
                          "com.mysql:mysql-connector-j@9.7.0"],
                         [sd.coordinate(c) for c in result])


class AggregateTest(unittest.TestCase):

    def test_fixed_versions_dedup(self):
        vuln = {"affected": [
            {"ranges": [{"events": [{"fixed": "2.0.0"}, {"introduced": "0"}]}]},
            {"ranges": [{"events": [{"fixed": "2.0.0"}, {"fixed": "2.1.0"}]}]},
        ]}
        self.assertEqual(["2.0.0", "2.1.0"], sd.fixed_versions(vuln))

    def test_worst_level(self):
        vulns = [{"database_specific": {"severity": "LOW"}},
                 {"database_specific": {"severity": "CRITICAL"}},
                 {"database_specific": {"severity": "HIGH"}}]
        self.assertEqual("CRITICAL", sd.worst_level(vulns))

    def test_summarize_counts(self):
        vulns = [{"database_specific": {"severity": "HIGH"}},
                 {"database_specific": {"severity": "HIGH"}}]
        counts = sd.summarize(vulns)
        self.assertEqual(2, counts["HIGH"])


class ScanFailClosedTest(unittest.TestCase):
    """最重要的断言：网络失败绝不能被当成「没有漏洞」。"""

    SBOM = {"components": [
        {"group": "org.telegram", "name": "telegrambots-meta", "version": "10.3.0"},
    ]}

    def test_network_error_propagates(self):
        original = sd._request_json

        def boom(*args, **kwargs):
            raise urllib.error.URLError("network down")

        sd._request_json = boom
        try:
            with self.assertRaises(urllib.error.URLError):
                sd.scan(self.SBOM, pause=0, log=lambda *_: None)
        finally:
            sd._request_json = original

    def test_result_count_mismatch_is_an_error(self):
        original = sd._request_json

        def short(*args, **kwargs):
            return {"results": []}   # 请求 1 个组件却返回 0 条

        sd._request_json = short
        try:
            with self.assertRaises(OSError):
                sd.scan(self.SBOM, pause=0, log=lambda *_: None)
        finally:
            sd._request_json = original


class ScanHappyPathTest(unittest.TestCase):

    SBOM = {"bomFormat": "CycloneDX", "specVersion": "1.5", "components": [
        {"group": "com.foo", "name": "bar", "version": "1.0.0"},
        {"group": "com.baz", "name": "qux", "version": "2.0.0"},
    ]}

    def test_report_shape(self):
        original = sd._request_json

        def fake(url, payload=None, timeout=60):
            if url == sd.OSV_BATCH_URL:
                results = []
                for query in payload["queries"]:
                    if query["package"]["name"] == "com.foo:bar":
                        results.append({"vulns": [{"id": "GHSA-1111-2222-3333"}]})
                    else:
                        results.append({})
                return {"results": results}
            if url.startswith(sd.OSV_VULN_URL):
                return {"id": url[len(sd.OSV_VULN_URL):],
                        "aliases": ["CVE-2024-0001"],
                        "database_specific": {"severity": "HIGH"},
                        "summary": "demo vulnerability",
                        "affected": [{"ranges": [{"events": [{"fixed": "1.0.1"}]}]}]}
            raise AssertionError("unexpected url: " + url)

        sd._request_json = fake
        try:
            report = sd.scan(self.SBOM, pause=0, log=lambda *_: None)
        finally:
            sd._request_json = original

        self.assertEqual(2, report["scannedComponents"])
        self.assertEqual(1, report["vulnerableComponents"])
        self.assertEqual(1, report["totalVulnerabilities"])
        self.assertEqual("HIGH", report["worstSeverity"])
        self.assertEqual("com.foo:bar@1.0.0", report["components"][0]["component"])
        self.assertEqual(["1.0.1"], report["vulnerabilities"][0]["fixedVersions"])
        self.assertEqual(["CVE-2024-0001"], report["vulnerabilities"][0]["aliases"])

    def test_markdown_does_not_lie_when_empty(self):
        report = {"generatedAt": "2026-01-01T00:00:00+00:00",
                  "sbomFormat": "CycloneDX", "sbomSpecVersion": "1.5",
                  "scannedComponents": 3, "vulnerableComponents": 0,
                  "totalVulnerabilities": 0,
                  "severityCounts": {k: 0 for k in sd.SEVERITY_RANK},
                  "worstSeverity": "UNKNOWN", "vulnerabilities": [],
                  "components": []}
        md = sd.render_markdown(report, "target/bom.json")
        self.assertIn("未发现", md)
        self.assertIn("无命中 ≠ 无漏洞", md)


if __name__ == "__main__":
    unittest.main(verbosity=2)
