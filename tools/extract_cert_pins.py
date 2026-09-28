import subprocess, sys, hashlib, base64

def get_pin(host, port=443):
    cmd = [
        'openssl', 's_client', '-connect', f'{host}:{port}',
        '-servername', host, '-showcerts'
    ]
    proc = subprocess.run(cmd, capture_output=True, input=b'', timeout=15)
    output = proc.stdout.decode(errors='ignore')

    cert_lines = []
    in_cert = False
    for line in output.split('\n'):
        if '-----BEGIN CERTIFICATE-----' in line:
            in_cert = True
            cert_lines = [line]
        elif in_cert:
            cert_lines.append(line)
            if '-----END CERTIFICATE-----' in line:
                break

    if not cert_lines:
        print(f"Failed to extract certificate from {host}:{port}")
        return None

    cert_pem = '\n'.join(cert_lines)
    proc = subprocess.run(
        ['openssl', 'x509', '-pubkey', '-noout'],
        input=cert_pem.encode(), capture_output=True, timeout=5
    )
    pubkey_pem = proc.stdout
    proc = subprocess.run(
        ['openssl', 'pkey', '-pubin', '-outform', 'der'],
        input=pubkey_pem, capture_output=True, timeout=5
    )
    pubkey_der = proc.stdout
    sha256 = hashlib.sha256(pubkey_der).digest()
    pin = base64.b64encode(sha256).decode()
    return f'sha256/{pin}'

if __name__ == '__main__':
    hosts = sys.argv[1:] if len(sys.argv) > 1 else ['clove.dpdns.org']  # Legacy — kept for historical reference
    for host in hosts:
        port = 443
        if ':' in host:
            host, port = host.split(':')
            port = int(port)
        pin = get_pin(host, port)
        if pin:
            print(f'{host} -> {pin}')
            print(f'{host} (backup) -> {pin}')
