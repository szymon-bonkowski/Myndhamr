#!/usr/bin/env python3
"""Fail if AGP reports success without actually executing JNI tests."""
import pathlib
import argparse
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--require-capture', action='store_true')
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parent.parent
reports = root / 'androidApp/build/outputs/androidTest-results/connected/debug'
count = 0
capture_count = 0
for report in reports.glob('TEST-*.xml'):
    suites = ET.parse(report).getroot()
    count += int(suites.get('tests', '0'))
    assert int(suites.get('failures', '0')) == 0, report
    assert int(suites.get('errors', '0')) == 0, report
    assert int(suites.get('skipped', '0')) == 0, report
    capture_count += sum(case.get('classname', '').endswith('.CaptureDeviceTest') for case in suites.iter('testcase'))
assert count >= 2, f'Expected at least 2 executed Android JNI tests; found {count}. Check device installation permissions.'
if args.require_capture:
    assert capture_count >= 1, 'Expected an executed physical CaptureDeviceTest; none was reported'
print(f'Android JNI gate: {count} tests executed without failures or skips')
