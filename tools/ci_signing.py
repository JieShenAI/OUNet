"""Materialize the existing Android signing secrets for a trusted CI build."""
import base64
import os
from pathlib import Path


def main():
    names=['OUC_KEYSTORE_BASE64','OUC_STORE_PASSWORD','OUC_KEY_ALIAS','OUC_KEY_PASSWORD']
    if any(not os.environ.get(n) for n in names):raise SystemExit('Configure the four existing Android signing secrets first.')
    root=Path(__file__).resolve().parents[1]/'android'
    (root/'.signing').mkdir(exist_ok=True)
    (root/'.signing/release.jks').write_bytes(base64.b64decode(os.environ[names[0]],validate=True))
    def escape(value):return value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r').replace('=','\\=').replace(':','\\:')
    (root/'keystore.properties').write_text('\n'.join([
        'storeFile=.signing/release.jks','storePassword='+escape(os.environ[names[1]]),
        'keyAlias='+escape(os.environ[names[2]]),'keyPassword='+escape(os.environ[names[3]])])+'\n',encoding='utf-8')


if __name__=='__main__':main()
