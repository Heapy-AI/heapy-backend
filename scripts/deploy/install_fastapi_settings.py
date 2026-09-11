"""서버 안에서만 설정을 복호화하고 비밀 출력을 금지한다. 작성자: 김진우."""
import base64,json,os,re,sys
from pathlib import Path
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
AAD=b'heapy-fastapi:i-055b8632e5b93fd96:20260909'
def unseal(envelope,private):
    decode=lambda key:base64.b64decode(envelope[key],validate=True)
    key=private.decrypt(decode('key'),padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),algorithm=hashes.SHA256(),label=AAD))
    return json.loads(AESGCM(key).decrypt(decode('nonce'),decode('data'),AAD))
def main():
    assert os.geteuid()==0
    root=Path('/run/heapy-fastapi-bootstrap-20260909')
    target=Path('/opt/heapy/fastapi.env')
    backend=Path('/opt/heapy/backend.env')
    backup=Path('/opt/heapy/backend.env.pre-chat-20260909')
    assert not target.exists() and not target.is_symlink() and not backup.exists()
    assert not backend.is_symlink() and backend.stat().st_uid==0 and backend.stat().st_mode&0o777==0o600
    original=backend.read_bytes()
    assert not any(line.startswith(b'CHAT_') for line in original.splitlines())
    private=serialization.load_pem_private_key((root/'key.pem').read_bytes(),None)
    envelope=json.loads(sys.stdin.read(16385))
    settings=unseal(envelope,private)
    assert set(settings)<=set(('GEMINI_API_KEY','PINECONE_API_KEY','PINECONE_INDEX_NAME','INTERNAL_SERVICE_TOKEN'))
    assert all(settings.get(k) for k in ('GEMINI_API_KEY','PINECONE_API_KEY','INTERNAL_SERVICE_TOKEN'))
    assert all(isinstance(v,str) and v==v.strip() and '\n' not in v and '\r' not in v for v in settings.values())
    token=settings['INTERNAL_SERVICE_TOKEN']
    assert re.fullmatch(r'[A-Za-z0-9_-]{64}',token)
    fastapi=''.join(k+'='+v+'\n' for k,v in settings.items()).encode()
    updated=original.rstrip(b'\r\n')+b'\nCHAT_ENABLED=false\nCHAT_BASE_URL=http://heapy-fastapi:8000\nCHAT_INTERNAL_TOKEN='+token.encode()+b'\n'
    os.umask(0o077)
    for path,content in ((backup,original),(target,fastapi),(backend.with_suffix('.env.chat-new'),updated)):
        with path.open('xb') as out: out.write(content)
        os.chmod(path,0o600)
    os.replace(backend.with_suffix('.env.chat-new'),backend)
    assert target.stat().st_mode&0o777==0o600 and backend.stat().st_mode&0o777==0o600
    (root/'key.pem').unlink()
    root.rmdir()
    print('FASTAPI_SETTINGS_INSTALLED; CHAT_DISABLED; BACKEND_SETTINGS_PRESERVED')
if __name__=='__main__':
    try: main()
    except Exception: raise SystemExit('설정 설치 실패. 값·예외 원문 비공개. 상태 확인 전 재시도 금지.') from None
