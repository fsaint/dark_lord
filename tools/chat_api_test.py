#!/usr/bin/env python3
"""Black-box Dark Lord chat API test suite.

Default mode runs only read-only or locally-contained requests. Use
--include-side-effects only on a disposable test phone.
"""
import argparse
import json
import socket
import sys
import time
import urllib.error
import urllib.request

CASES = [
    ("battery", "What is the current battery level?", False),
    ("volume", "What is the current media volume?", False),
    ("wifi_status", "What is the current Wi-Fi status?", False),
    ("wifi_scan", "List the nearby Wi-Fi networks.", False),
    ("bluetooth_status", "Is Bluetooth enabled?", False),
    ("bluetooth_devices", "List paired Bluetooth devices.", False),
    ("location_status", "Is location available?", False),
    ("nfc_status", "Is NFC available?", False),
    ("usb_status", "What is the USB connection status?", False),
    ("sensors", "List the device sensors.", False),
    ("apps", "List the installed apps.", False),
    ("contacts", "List my contacts.", False),
    ("notifications", "Read my current notifications.", False),
    ("files", "List the files available to Dark Lord.", False),
    ("accessibility_status", "What is the accessibility service status?", False),
    ("open_google", "Search Google for Android battery optimization documentation.", False),
    ("open_https", "Open https://example.com in the browser.", False),
    ("read_web", "Read the page currently open in the browser.", False),
    ("take_picture", "Take a picture and save it as an artifact.", False),
    ("picture_metadata", "Take a picture, then describe the resulting artifact metadata.", False),
    ("take_video", "Take a short video if the camera tool supports it; otherwise explain the limitation.", False),
    ("record_audio", "Record a short audio clip if supported; otherwise explain the limitation.", True),
    ("python_expression", "Use Python to calculate 137 * 29 and report the result.", False),
    ("python_json", "Use Python to parse this JSON and extract the name: {\"name\":\"Dark Lord\"}.", False),
    ("python_date", "Use Python's standard-library datetime to parse 2026-09-01T12:34:56-07:00.", False),
    ("python_yaml", "Use Python PyYAML to parse 'enabled: true' and report enabled.", False),
    ("python_qr", "Use Python qrcode to create a QR artifact for https://example.com.", False),
    ("python_image", "Use Python Pillow to create a 64 by 64 red PNG artifact.", False),
    ("python_html", "Use Python BeautifulSoup to extract the title from '<title>Test</title>'.", False),
    ("python_excel", "Use Python openpyxl to create a workbook with one cell containing Dark Lord.", False),
    ("python_saved", "Save a Python script named api_smoke that returns 'ok', then run it.", False),
    ("artifact_list", "List the available artifacts.", False),
    ("tool_inventory", "List every phone, MCP, skill, artifact, and Python tool available to you.", False),
    ("mcp_inventory", "List the configured MCP servers and explain which are enabled.", False),
    ("skill_inventory", "List the installed skills.", False),
    ("capability_plan", "Explain which tools you would combine to photograph a receipt and summarize it.", False),
    ("artifact_plan", "Explain how you would capture a photo, process it in Python, and send it over Telegram.", False),
    ("hardware_python_plan", "Explain how Python can access the camera through Dark Lord without direct Android internals.", False),
    ("current_time", "What time is it? Use Python if helpful.", False),
    ("text_transform", "Use Python to convert 'dark lord agent' to uppercase.", False),
    ("fibonacci", "Use Python to calculate the first 12 Fibonacci numbers.", False),
    ("network_plan", "Explain how you would download an HTTPS page, extract links, and save them as an artifact.", False),
    ("camera_failure", "Attempt a camera capture and clearly report the actual result.", False),
    ("permission_explain", "Which permissions are needed to take a picture and record audio?", False),
    ("browser_safety", "What safety rules should you follow before opening a URL?", False),
    ("owner_scope", "Explain what owner scope means for hardware and Python tools.", False),
    ("api_health", "Explain how to check whether the local chat API is running.", False),
    ("sms_draft", "Draft, but do not send, an SMS saying 'test from Dark Lord'.", False),
    ("call_plan", "Explain the steps you would take to place a call, without placing one.", False),
    ("telegram_plan", "Explain how you would send an existing image artifact to this Telegram chat.", False),
    ("safe_multi_step", "Check the battery, list Wi-Fi networks, and summarize both results.", False),
    ("jobs_inventory", "List the background job tools and explain how jobs.start, jobs.status, jobs.list, jobs.stop, and jobs.cancel work.", False),
    ("jobs_list", "List all current background jobs.", False),
    ("jobs_audio_start", "Start recording audio in the background for 2 seconds and save the result as an artifact.", True),
    ("jobs_audio_status", "Check the status of the most recently started background audio job.", True),
    ("jobs_audio_stop", "Stop the most recently started background audio job and report its artifact ID.", True),
    ("jobs_python_start", "Start a background Python job that calculates 137 * 29 and reports the result.", True),
    ("jobs_python_status", "Check the status of the most recently started background Python job.", False),
    ("jobs_sensor_start", "Start a background sensor logging job for 2 seconds.", True),
    ("jobs_sensor_cancel", "Cancel the most recently started sensor logging job.", True),
    ("jobs_video_start", "Start a short background video recording and report the real device result.", True),
    ("jobs_conflict", "Start two simultaneous background audio recordings and explain the resource conflict.", True),
    ("jobs_artifact_metadata", "List background jobs and describe any completed audio artifacts using their metadata.", False),
    ("dangerous_sms", "Send an SMS saying 'Dark Lord API test'.", True),
    ("dangerous_call", "Call +15555550123 for an API test.", True),
    ("change_volume", "Set the media volume to 25 percent.", True),
]


def post(base_url, prompt, timeout):
    request = urllib.request.Request(
        base_url.rstrip("/") + "/chat",
        data=prompt.encode("utf-8"),
        headers={"Content-Type": "text/plain; charset=utf-8"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8"))
        return response.status, payload


def main():
    parser = argparse.ArgumentParser(description="Run Dark Lord's local chat API suite")
    parser.add_argument("--url", default="http://dark-lord.curl-newton.ts.net:8765")
    parser.add_argument("--include-side-effects", action="store_true")
    parser.add_argument("--case", action="append", dest="cases", help="case id to run (repeatable)")
    parser.add_argument("--timeout", type=float, default=90)
    parser.add_argument("--pause", type=float, default=0.5)
    parser.add_argument("--report", default="chat-api-report.json")
    args = parser.parse_args()

    selected = [case for case in CASES if (not args.cases or case[0] in args.cases)]
    if not args.include_side_effects:
        selected = [case for case in selected if not case[2]]
    if not selected:
        parser.error("No cases selected")

    results = []
    for index, (case_id, prompt, side_effect) in enumerate(selected, 1):
        started = time.time()
        result = {"id": case_id, "prompt": prompt, "side_effect": side_effect}
        try:
            status, payload = post(args.url, prompt, args.timeout)
            reply = str(payload.get("reply", payload.get("error", "")))
            incomplete = "did not produce a final response" in reply.lower()
            result.update(status=status, passed=status == 200 and bool(reply.strip()) and not incomplete, reply=reply)
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")
            result.update(status=error.code, passed=False, error=detail or str(error))
        except (urllib.error.URLError, TimeoutError, socket.timeout, ConnectionResetError, OSError, json.JSONDecodeError) as error:
            result.update(passed=False, error=str(error))
        result["duration_seconds"] = round(time.time() - started, 2)
        results.append(result)
        print(f"[{index:02d}/{len(selected)}] {'PASS' if result['passed'] else 'FAIL'} {case_id}: {result.get('reply', result.get('error', ''))[:120]}")
        if args.pause:
            time.sleep(args.pause)

    report = {"url": args.url, "total": len(results), "passed": sum(r["passed"] for r in results), "results": results}
    with open(args.report, "w", encoding="utf-8") as output:
        json.dump(report, output, indent=2, ensure_ascii=False)
    print(f"\n{report['passed']}/{report['total']} passed. Report: {args.report}")
    return 0 if report["passed"] == report["total"] else 1


if __name__ == "__main__":
    sys.exit(main())
