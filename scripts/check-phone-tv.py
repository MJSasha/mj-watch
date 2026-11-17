#!/usr/bin/env python3
"""Real APK/HTTPS/MediaSession check on four disposable issue26 AVDs (no Cast claim)."""
import argparse
import json
import os
from pathlib import Path
import signal
import http.server
import threading
import re
import ssl
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ADB = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb'


def run(args, **kwargs):
    return subprocess.run([str(v) for v in args], check=True, text=True, capture_output=True, **kwargs).stdout


def adb(serial, *args):
    return run([ADB, '-s', serial, *args])


def instrument(serial, package, test, folder, **arguments):
    cmd = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', test, '-e', 'isolatedRemote', 'true']
    for key, value in arguments.items():
        cmd += ['-e', key, value]
    output = adb(serial, *cmd, package + '.test/androidx.test.runner.AndroidJUnitRunner')
    (folder / (serial + '-' + test.rsplit('.', 1)[-1] + '.log')).write_text(output)
    if 'FAILURES' in output or 'INSTRUMENTATION_FAILED' in output or 'OK (' not in output:
        raise RuntimeError(output)
    print(output.strip(), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phone', default='emulator-5570')
    parser.add_argument('--phone2', default='emulator-5576')
    parser.add_argument('--tv1', default='emulator-5572')
    parser.add_argument('--tv2', default='emulator-5574')
    parser.add_argument('--video', type=Path, required=True, help='Test MP4 at least 3 minutes long')
    args = parser.parse_args()
    folder = ROOT / 'dist/phone-tv' / time.strftime('%Y%m%d-%H%M%S')
    folder.mkdir(parents=True)
    # Only the AVDs created for this task may be reset by this runner.
    for serial, name in [(args.phone, 'issue26-phone'),(args.phone2,'issue26-phone2'), (args.tv1, 'issue26-tv1'), (args.tv2, 'issue26-tv2')]:
        if adb(serial, 'emu', 'avd', 'name').splitlines()[0].strip() != name:
            raise RuntimeError('Refusing to reset an unrelated device: ' + serial)
        if adb(serial, 'shell', 'getprop', 'sys.boot_completed').strip() != '1':
            raise RuntimeError('Wait for Android to boot: ' + serial)
        adb(serial, 'shell', 'settings', 'put', 'global', 'window_animation_scale', '0')
        adb(serial, 'shell', 'settings', 'put', 'global', 'transition_animation_scale', '0')
        adb(serial, 'shell', 'settings', 'put', 'global', 'animator_duration_scale', '0')
        adb(serial, 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        adb(serial, 'shell', 'wm', 'dismiss-keyguard')
        adb(serial, 'reverse', 'tcp:19443', 'tcp:19443')
        adb(serial, 'reverse', 'tcp:18444', 'tcp:19444')
        adb(serial, 'reverse', 'tcp:18445', 'tcp:19445')
    adb(args.phone,'shell','wm','size','1080x2340')
    adb(args.phone,'shell','wm','density','420')
    fixture = folder / 'fixture'
    run(['go', 'build', '-o', fixture, './cmd/fixture'], cwd=ROOT / 'server')
    log = (folder / 'fixture.log').open('w')
    process = subprocess.Popen([str(fixture), str(folder / 'state'), str(args.video.resolve())], stdout=log, stderr=subprocess.STDOUT, env={**os.environ, 'MJWATCH_FIXTURE_REMOTE': '1', 'MJWATCH_FIXTURE_PORT': '19443', 'MJWATCH_FIXTURE_CONTROL_PORT': '19444'}, start_new_session=True)
    try:
        deadline = time.monotonic() + 15
        while True:
            try:
                with urllib.request.urlopen('http://127.0.0.1:19444/bootstrap', timeout=1) as response:
                    observer = json.load(response)
                break
            except OSError:
                if time.monotonic() > deadline:
                    raise RuntimeError('Fixture did not start')
                time.sleep(.2)
        class NetworkProbe(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                from urllib.parse import urlsplit, parse_qs
                url = urlsplit(self.path)
                result = {'ok': True}
                if url.path == '/cut':
                    adb(args.phone, 'reverse', '--remove', 'tcp:19443')
                elif url.path == '/restore':
                    adb(args.phone, 'reverse', 'tcp:19443', 'tcp:19443')
                elif url.path in ['/tv-pause', '/tv-play']:
                    adb(args.tv1, 'shell', 'input', 'keyevent', 'KEYCODE_MEDIA_PAUSE' if url.path == '/tv-pause' else 'KEYCODE_MEDIA_PLAY')
                elif url.path == '/second-controller':
                    query=parse_qs(url.query)
                    target=query.get('target',[''])[0];session=query.get('session',[''])[0]
                    if not all(re.fullmatch('[A-Za-z0-9_-]{16,128}',value) for value in [target,session]):
                        self.send_error(400);return
                    instrument(args.phone2,'dev.mjwatch.mobile','dev.mjwatch.mobile.SecondControllerTest',folder,receiver=target,session=session)
                elif url.path == '/state':
                    query = parse_qs(url.query)
                    target = query.get('target', [''])[0]
                    instance = query.get('instance', [''])[0]
                    if not all(re.fullmatch('[A-Za-z0-9_-]{16,128}', value) for value in [target, instance]):
                        self.send_error(400)
                        return
                    req = urllib.request.Request('https://127.0.0.1:19443/api/v1/remote/' + target + '?instance=' + instance, headers={'Authorization': 'Bearer ' + observer['accessToken']})
                    tls = ssl.create_default_context(cafile=str(folder / 'state/ca.crt'))
                    with urllib.request.urlopen(req, context=tls, timeout=3) as response:
                        result = json.load(response)
                else:
                    self.send_error(404)
                    return
                data = json.dumps(result).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        probe = http.server.ThreadingHTTPServer(('127.0.0.1', 19445), NetworkProbe)
        threading.Thread(target=probe.serve_forever, daemon=True).start()
        for serial, app in [(args.tv1, 'tv'), (args.tv2, 'tv'), (args.phone, 'mobile'),(args.phone2,'mobile')]:
            package = 'dev.mjwatch.' + app
            print('Installing isolated ' + serial, flush=True)
            adb(serial, 'install', '-r', ROOT / f'app-{app}/build/outputs/apk/debug/app-{app}-debug.apk')
            adb(serial, 'install', '-r', ROOT / f'app-{app}/build/outputs/apk/androidTest/debug/app-{app}-debug-androidTest.apk')
            adb(serial, 'shell', 'pm', 'clear', package)
        for serial, name in [(args.tv1, 'Гостиная'), (args.tv2, 'Спальня')]:
            instrument(serial, 'dev.mjwatch.tv', 'dev.mjwatch.tv.RemoteReceiverSetupTest', folder, receiverName=name)
            adb(serial, 'shell', 'am', 'start', '-n', 'dev.mjwatch.tv/.MainActivity')
        print('Testing actual phone UI and Android MediaSession against both TV APKs', flush=True)
        instrument(args.phone, 'dev.mjwatch.mobile', 'dev.mjwatch.mobile.PhoneTvTest', folder)
        proof_path='/sdcard/Android/data/dev.mjwatch.mobile/files/phone-tv-proof'
        adb(args.phone,'pull',proof_path,folder/'screenshots-phone')
        print('Checking 200% fonts and tablet dialog geometry',flush=True)
        adb(args.phone,'shell','settings','put','system','font_scale','2.0')
        instrument(args.phone,'dev.mjwatch.mobile','dev.mjwatch.mobile.PhoneTvTest#renameInUnifiedDevicesDialogAndLargeFont',folder)
        adb(args.phone,'pull',proof_path,folder/'screenshots-font200')
        adb(args.phone,'shell','settings','put','system','font_scale','1.0')
        adb(args.phone,'shell','wm','size','1600x2560')
        adb(args.phone,'shell','wm','density','240')
        instrument(args.phone,'dev.mjwatch.mobile','dev.mjwatch.mobile.PhoneTvTest#renameInUnifiedDevicesDialogAndLargeFont',folder)
        adb(args.phone,'pull',proof_path,folder/'screenshots-tablet')
        adb(args.phone,'shell','wm','size','1080x2340');adb(args.phone,'shell','wm','density','420')
        adb(args.phone,'shell','settings','put','secure','enabled_accessibility_services','com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService')
        adb(args.phone,'shell','settings','put','secure','accessibility_enabled','1')
        instrument(args.phone,'dev.mjwatch.mobile','dev.mjwatch.mobile.TalkBackFocusTest',folder)
        adb(args.phone,'pull',proof_path,folder/'screenshots-talkback')
        adb(args.phone,'shell','settings','put','secure','enabled_accessibility_services','null')
        adb(args.phone,'shell','settings','put','secure','accessibility_enabled','0')
        instrument(args.tv1,'dev.mjwatch.tv','dev.mjwatch.tv.DeviceRenameTvTest',folder)
        adb(args.tv1,'pull','/sdcard/Android/data/dev.mjwatch.tv/files/phone-tv-proof',folder/'screenshots-tv')
        print('PASSED. Evidence: '  + str(folder), flush=True)
    finally:
        # Preserve actual failure screens/logs as well as successful runs.
        for serial, package in [(args.phone,'dev.mjwatch.mobile'),(args.phone2,'dev.mjwatch.mobile'),(args.tv1,'dev.mjwatch.tv'),(args.tv2,'dev.mjwatch.tv')]:
            try:
                (folder / (serial+'-media-session.txt')).write_text(adb(serial,'shell','dumpsys','media_session'))
                (folder / (serial+'-logcat.txt')).write_text(adb(serial,'logcat','-d','-t','3000'))
                if serial==args.phone:
                    subprocess.run([str(ADB),'-s',serial,'pull','/sdcard/Android/data/'+package+'/files/phone-tv-proof',str(folder/'screenshots')],capture_output=True)
                with (folder/(serial+'.png')).open('wb') as output:
                    subprocess.run([str(ADB),'-s',serial,'exec-out','screencap','-p'],stdout=output,check=True)
            except subprocess.CalledProcessError as error:
                (folder/(serial+'-capture-error.txt')).write_text(str(error))
        for command in [('settings','put','secure','enabled_accessibility_services','null'),('settings','put','secure','accessibility_enabled','0'),('settings','put','system','font_scale','1.0'),('wm','size','reset'),('wm','density','reset')]:
            subprocess.run([str(ADB),'-s',args.phone,'shell',*command],capture_output=True)
        if 'probe' in locals():
            probe.shutdown();probe.server_close()
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=10)
        log.close()
        for serial in [args.phone,args.phone2, args.tv1, args.tv2]:
            for port in [19443, 18444, 18445]:
                subprocess.run([str(ADB), '-s', serial, 'reverse', '--remove', 'tcp:' + str(port)], capture_output=True)


if __name__ == '__main__':
    main()
