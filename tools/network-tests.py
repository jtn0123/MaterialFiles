#!/usr/bin/env python3
"""Run all JVM tests against real loopback FTP, FTPS, WebDAV and a disposable SMB server."""
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid

root = Path(__file__).resolve().parents[1]
name = 'materialfiles-test-' + uuid.uuid4().hex[:12]
image = 'materialfiles-smb-tests:local'
dockerfile = '''FROM alpine:3.23@sha256:075c2c1a4068c1e251228ca2b0ea8b163f835ca5d66e758b93c7fc89b1995616
RUN apk add --no-cache samba samba-client && adduser -D test && mkdir /share && \\
    chown test:test /share && echo hello > /share/hello.txt && \\
    printf 'test-only\\ntest-only\\n' | smbpasswd -a -s test
RUN printf '[global]\\nserver min protocol = SMB2\\nmap to guest = Never\\n[test]\\npath = /share\\nread only = no\\nvalid users = test\\n' > /etc/samba/smb.conf
CMD ["smbd", "--foreground", "--no-process-group", "--debug-stdout"]
'''
subprocess.run(['docker', 'build', '-t', image, '-'], input=dockerfile, text=True, check=True)
try:
    subprocess.run(['docker', 'run', '-d', '--name', name, '-p', '127.0.0.1::445', image], check=True)
    deadline = time.monotonic() + 45
    while True:
        probe = subprocess.run(['docker', 'exec', name, 'smbclient', '-L', 'localhost', '-U', 'test%test-only'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if probe.returncode == 0:
            break
        if time.monotonic() >= deadline:
            raise RuntimeError('SMB fixture did not become ready')
        time.sleep(0.5)
    address = subprocess.check_output(['docker', 'port', name, '445/tcp'], text=True).strip()
    env = dict(os.environ, MATERIAL_SMB_PORT=address.rsplit(':', 1)[1], MATERIAL_SMB_CONTAINER=name)
    subprocess.run([str(root / 'gradlew'), ':app:testDebugUnitTest', *sys.argv[1:]], cwd=root, env=env, check=True)
finally:
    subprocess.run(['docker', 'rm', '-f', name], stdout=subprocess.DEVNULL, check=False)
