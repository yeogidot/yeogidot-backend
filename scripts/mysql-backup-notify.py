#!/usr/bin/env python3
"""Report backup status only; never send DB contents, URLs or raw error logs."""
import os
import re
import sys
import time
import urllib.request

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def send_ping(base, outcome, opener=None, pause=time.sleep, kind='db'):
    if kind not in ('db', 'photos'):
        raise ValueError('Invalid backup kind')
    if outcome not in ('success', 'fail'):
        raise ValueError('Invalid outcome')
    if not re.fullmatch(r'https://hc-ping\.com/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}',base):
        raise ValueError('Invalid monitoring endpoint')
    url = base + ('/fail' if outcome == 'fail' else '')
    # Fixed operational status; no backup contents, secrets, filenames or stack traces.
    payload = b'Yeogidot DB backup or R2 upload failed' if outcome == 'fail' else b'Yeogidot DB backup and encrypted R2 upload verified'
    if kind == 'photos':
        payload = b'Yeogidot photo backup failed' if outcome == 'fail' else b'Yeogidot encrypted photo backup verified'
    opener = opener or urllib.request.build_opener(NoRedirect())
    for attempt in range(3):
        try:
            request = urllib.request.Request(url, data=payload, headers={'Content-Type':'text/plain'},method='POST')
            with opener.open(request, timeout=5) as response:
                if response.status != 200 or response.read(128).strip() != b'OK':
                    raise RuntimeError('Ping not accepted')
            return
        except Exception:
            if attempt == 2:
                raise RuntimeError('Monitoring delivery failed') from None
            pause(1)

def main():
    if len(sys.argv) != 2:
        raise ValueError('Expected success or fail')
    send_ping(os.environ['HEALTHCHECKS_PING_URL'],sys.argv[1],kind=os.environ.get('HEALTHCHECKS_KIND','db'))
    print('HEALTHCHECKS_SENT outcome='+sys.argv[1])

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('HEALTHCHECKS_SEND_FAILED type='+type(error).__name__,file=sys.stderr)
        sys.exit(1)
