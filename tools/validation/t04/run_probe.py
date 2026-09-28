"""Run only against a disposable loopback WebDAV root; always stop the server."""
import argparse
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser()
parser.add_argument('--gradle', required=True)
parser.add_argument('--wsgidav', required=True)
args = parser.parse_args()
with tempfile.TemporaryDirectory(prefix='t04-dav-') as root:
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    with tempfile.TemporaryFile(mode='w+') as log:
        server = subprocess.Popen([
            args.wsgidav, '--no-config', '--host=127.0.0.1', f'--port={port}',
            f'--root={root}', '--auth=anonymous', '--server=cheroot', '-q',
        ], stdout=log, stderr=subprocess.STDOUT)
        try:
            for attempt in range(100):
                if server.poll() is not None:
                    log.seek(0)
                    raise RuntimeError(log.read())
                try:
                    with socket.create_connection(('127.0.0.1', port), timeout=0.2):
                        break
                except OSError:
                    time.sleep(0.1)
            else:
                raise RuntimeError('WebDAV fixture did not start')
            env = os.environ.copy()
            env['T04_WEBDAV_ENDPOINT'] = f'http://127.0.0.1:{port}'
            subprocess.run([
                args.gradle, '-p', str(Path(__file__).parent.resolve()),
                '--no-daemon', '--console=plain', 'run',
            ], env=env, check=True, timeout=600)
        finally:
            server.terminate()
            try:
                server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
