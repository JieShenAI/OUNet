"""Create/verify a GitHub draft from a validated bundle. Public publish is explicit.

Uses GITHUB_TOKEN in Actions, otherwise the host's Git credential helper. Never
prints credentials; authenticated requests are limited to this repository's API.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from release_bundle import verify, digest, REPO

REPOSITORY='JieShenAI/OUNet'
API='https://api.github.com/repos/'+REPOSITORY


def release_edit(manifest, **changes):
    # GitHub can reset a draft's uncreated tag to its untagged placeholder
    # when PATCH omits tag_name. Preserve both identity fields on every edit.
    return dict(changes,tag_name=manifest['tag'],target_commitish=manifest['sourceCommit'])


def credential():
    token=os.environ.get('GITHUB_TOKEN') or os.environ.get('GH_TOKEN')
    if token:return token
    env=dict(os.environ,GIT_TERMINAL_PROMPT='0',GCM_INTERACTIVE='Never')
    result=subprocess.run(['git','credential','fill'],input='protocol=https\nhost=github.com\n\n',text=True,capture_output=True,env=env)
    fields=dict(line.split('=',1) for line in result.stdout.splitlines() if '=' in line)
    if result.returncode or not fields.get('password'):raise RuntimeError('GitHub publishing authentication is unavailable.')
    return fields['password']


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):return None


class GitHub:
    def __init__(self,token):
        self.token=token
        # Match the clients: never send publishing credentials through an ambient proxy.
        self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect)
    def request(self,path,method='GET',data=None,content_type='application/json',anonymous=False,raw=False):
        url=path if path.startswith('https://') else API+path
        for _ in range(5):
            parsed=urllib.parse.urlsplit(url)
            if parsed.scheme!='https' or parsed.username or parsed.port not in (None,443):raise RuntimeError('Unsafe GitHub URL')
            allowed=(parsed.hostname=='api.github.com' and parsed.path.startswith('/repos/'+REPOSITORY+'/')
                     or parsed.hostname=='uploads.github.com' and parsed.path.startswith('/repos/'+REPOSITORY+'/')
                     or method=='GET' and parsed.hostname=='release-assets.githubusercontent.com'
                     or method=='GET' and parsed.hostname=='github.com' and parsed.path.startswith('/'+REPOSITORY+'/releases/download/'))
            if not allowed:raise RuntimeError('Unexpected GitHub destination')
            headers={'User-Agent':'OUNet-Release-Publisher','Accept':'application/octet-stream' if raw else 'application/vnd.github+json',
                     'X-GitHub-Api-Version':'2022-11-28'}
            if not anonymous and parsed.hostname in ('api.github.com','uploads.github.com'):
                headers['Authorization']='Bearer '+self.token
            if data is not None:headers['Content-Type']=content_type
            body=json.dumps(data).encode() if isinstance(data,dict) else data
            try:
                with self.opener.open(urllib.request.Request(url,data=body,headers=headers,method=method),timeout=60) as response:
                    content=response.read()
                    return content if raw else json.loads(content) if content else None
            except urllib.error.HTTPError as error:
                if error.code in (301,302,303,307,308) and method=='GET':
                    url=urllib.parse.urljoin(url,error.headers.get('Location',''));continue
                if error.code==404:return None
                raise RuntimeError(f'GitHub API returned HTTP {error.code}; request not completed.') from None
        raise RuntimeError('Too many GitHub redirects')


def upload_draft(api,directory):
    manifest=verify(directory)
    if manifest['sourceDirty'] or not manifest['automatedChecksPassed']:
        raise ValueError('Publish only a clean source commit built with all automated checks.')
    sha=manifest['sourceCommit'];tag=manifest['tag']
    if api.request('/commits/'+sha) is None:raise ValueError('Push the exact source commit before creating a release.')
    ref=api.request('/git/ref/tags/'+tag)
    if ref is not None:
        obj=ref['object']
        if obj['type']=='tag':obj=api.request('/git/tags/'+obj['sha'])['object']
        if obj['type']!='commit' or obj['sha']!=sha:raise ValueError('Existing tag points at another source commit')
    release=api.request('/releases/tags/'+tag)
    if release is None:
        # Drafts may not be returned by the tag endpoint; enumerate with publish permissions.
        for page in range(1,11):
            items=api.request('/releases?per_page=100&page='+str(page)) or []
            release=next((r for r in items if r.get('tag_name')==tag),None)
            if release or len(items)<100:break
    if release is not None and not release['draft']:raise ValueError('Release already public; never replace published binaries')
    if release is not None and release.get('target_commitish')!=sha:
        raise ValueError('Existing draft targets another source commit; review it before resuming')
    notes=directory/('ANDROID_RELEASE_NOTES.md' if manifest['platform']=='android' else 'WINDOWS_RELEASE_NOTES.md')
    body=notes.read_text(encoding='utf-8')+'\n\n源码提交：`'+sha+'`。构建及未验收范围见同提交的 `docs/RELEASE_UPDATE_VERIFICATION.md`。\n'
    if release is None:
        release=api.request('/releases','POST',{'tag_name':tag,'target_commitish':sha,
                    'name':'OUNet '+('Android ' if manifest['platform']=='android' else 'Windows ')+manifest['version'],
                    'body':body,'draft':True,'prerelease':False,'make_latest':'false'})
    else:
        release=api.request('/releases/'+str(release['id']),'PATCH',release_edit(manifest,body=body))
    release_id=release['id']
    names=[entry['name'] for entry in manifest['files']]+['release-manifest.json']
    existing=[]
    for page in range(1,11):
        batch=api.request(f'/releases/{release_id}/assets?per_page=100&page={page}') or []
        existing.extend(batch)
        if len(batch)<100:break
    if len({a['name'] for a in existing})!=len(existing):raise ValueError('Duplicate remote asset names')
    if {a['name'] for a in existing}-set(names):raise ValueError('Draft contains unexpected assets; inspect manually')
    for name in names:
        local=directory/name;remote=next((a for a in existing if a['name']==name),None)
        if remote is None:
            url=f'https://uploads.github.com/repos/{REPOSITORY}/releases/{release_id}/assets?'+urllib.parse.urlencode({'name':name})
            remote=api.request(url,'POST',local.read_bytes(),'application/octet-stream')
        content=api.request('/releases/assets/'+str(remote['id']),raw=True)
        if content is None or hashlib.sha256(content).hexdigest()!=digest(local):raise ValueError('Remote asset differs: '+name)
        print('Verified remote asset:',name)
    return release,manifest


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle',type=Path,required=True)
    parser.add_argument('--publish',action='store_true',help='Publish the fully verified draft; requires upgrade review report')
    parser.add_argument('--review-report',type=Path)
    args=parser.parse_args()
    directory=args.bundle.resolve();manifest=verify(directory)
    if args.publish:
        if not args.review_report:parser.error('--publish requires --review-report')
        report=json.loads(args.review_report.read_text(encoding='utf-8'))
        if (report.get('sourceCommit')!=manifest['sourceCommit'] or report.get('tag')!=manifest['tag']
                or report.get('approvedForPublication') is not True):raise ValueError('Review report does not approve this exact source and platform')
    api=GitHub(credential());release,manifest=upload_draft(api,directory)
    if args.publish:
        release=api.request('/releases/'+str(release['id']),'PATCH',release_edit(manifest,draft=False,make_latest='false'))
        # Anonymous read-back and real downloads. A protocol/content failure withdraws the release.
        try:
            public=api.request('/releases/tags/'+manifest['tag'],anonymous=True)
            if public is None or public.get('draft') or public.get('prerelease'):raise ValueError('Published release is not publicly available')
            for entry in manifest['files']+[{"name":"release-manifest.json","sha256":digest(directory/'release-manifest.json')}]:
                url=f"{REPO}/releases/download/{manifest['tag']}/{entry['name']}"
                content=api.request(url,anonymous=True,raw=True)
                if content is None or hashlib.sha256(content).hexdigest()!=entry['sha256']:raise ValueError('Public download verification failed')
        except Exception:
            api.request('/releases/'+str(release['id']),'PATCH',release_edit(manifest,prerelease=True))
            raise
    result={'tag':manifest['tag'],'releaseId':release['id'],'draft':release['draft'],
            'url':release['html_url'],'sourceCommit':manifest['sourceCommit']}
    report_path=directory.parent/(manifest['tag']+'-github-report.json')
    report_path.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result))


if __name__=='__main__':main()
