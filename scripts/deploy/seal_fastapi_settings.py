"""지정 EC2 공개키로 허용된 설정만 암호화한다. 작성자: 김진우."""
import base64,json,secrets,sys
from pathlib import Path
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from dotenv import dotenv_values
AAD=b'heapy-fastapi:i-055b8632e5b93fd96:20260909'
def seal(values,public):
    key=AESGCM.generate_key(bit_length=256)
    nonce=secrets.token_bytes(12)
    wrapped=public.encrypt(key,padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),algorithm=hashes.SHA256(),label=AAD))
    encrypted=AESGCM(key).encrypt(nonce,json.dumps(values).encode(),AAD)
    return {k:base64.b64encode(v).decode() for k,v in dict(key=wrapped,nonce=nonce,data=encrypted).items()}
def main():
    public=serialization.load_pem_public_key(Path('build/fastapi-bootstrap-public.pem').read_bytes())
    values=dotenv_values('C:/Users/jinwo/heapy-ai-health/.env',interpolate=False)
    allowed={k:values[k] for k in ('GEMINI_API_KEY','PINECONE_API_KEY','PINECONE_INDEX_NAME') if values.get(k)}
    assert all(allowed.get(k) for k in ('GEMINI_API_KEY','PINECONE_API_KEY'))
    assert all('\n' not in v and '\r' not in v and v==v.strip() for v in allowed.values())
    allowed['INTERNAL_SERVICE_TOKEN']=secrets.token_urlsafe(48)
    Path('build/fastapi-bootstrap-envelope.json').write_text(json.dumps(seal(allowed,public)),encoding='utf-8')
    print('ENCRYPTED_ENVELOPE_READY')
if __name__=='__main__':
    try: main()
    except Exception: raise SystemExit('암호화 준비 실패. 비밀값 비공개.') from None
