"""Read synthetic v0.3.0 DPAPI data with the new code, without installing or networking."""
import argparse
import hashlib
import importlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(ROOT),str(ROOT.parent)]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--from-ref',default='e3fc48e')
    args=parser.parse_args()
    if not re.fullmatch('[a-f0-9]{7,40}',args.from_ref):raise ValueError('Use a fixed commit SHA')
    output=ROOT/'.cache';output.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output,prefix='synthetic-upgrade-') as temporary:
        work=Path(temporary);package=work/'legacy_ounet';package.mkdir()
        for name in ('__init__.py','storage.py','request_policy.py'):
            data=subprocess.check_output(['git','show',args.from_ref+':desktop/ouc_desktop/'+name],cwd=ROOT.parent)
            (package/name).write_bytes(data)
        sys.path.insert(0,str(work))
        legacy=importlib.import_module('legacy_ounet')
        old=importlib.import_module('legacy_ounet.storage')
        old_policy=importlib.import_module('legacy_ounet.request_policy')
        assert legacy.__version__=='0.3.0'
        data_dir=work/'data';data_dir.mkdir()
        credentials=old.CredentialStore(data_dir/'credentials.dat')
        first=credentials.put('','synthetic-student','001234-test-only','replace','主要账号')
        second=credentials.put('','synthetic-other','005678-test-only','replace','备用账号')
        original=credentials.read_all()
        old.Settings(configured=True,active_account_id=first,interval_ms=300,monitor=False,appearance='dark').save(data_dir/'settings.json')
        policy=old_policy.RequestPolicy(data_dir/'request-state.json')
        policy.reject_credential(first,original[first]['revision'],'密码错误')
        for _ in range(3):policy.failure('synthetic timeout')
        before={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in data_dir.iterdir()}
        from ouc_desktop.storage import CredentialStore,Settings
        from ouc_desktop.request_policy import RequestPolicy
        current=CredentialStore(data_dir/'credentials.dat')
        assert current.read_all()==original
        settings=Settings.read(data_dir/'settings.json')
        assert settings.active_account_id==first and settings.interval_ms==300 and settings.appearance=='dark' and not settings.monitor
        policy= RequestPolicy(data_dir/'request-state.json')
        assert policy.paused and policy.failures==3 and policy.credential_error(first,original[first]['revision'])=='密码错误'
        assert before=={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in data_dir.iterdir()}
        report={'fromVersion':legacy.__version__,'fromCommit':args.from_ref,'dpapiAccountsPreserved':True,
                'settingsPreserved':True,'pauseAndCredentialErrorPreserved':True,'dataReadWithoutChanges':True,
                'realAccountsUsed':False,'installerRun':False,'networkRequests':0}
        (output/'cross-version-data-report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print('Synthetic 0.3.0 → current DPAPI/settings/pause compatibility passed; no installation or network.')


if __name__=='__main__':main()
