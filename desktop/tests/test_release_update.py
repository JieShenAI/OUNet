"""Release lookup contracts with synthetic GitHub responses and no browser calls."""
import json
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit
from test_desktop import TempTest
from test_v02 import Session, Response
from ouc_desktop import __version__
from ouc_desktop.update import check_update, validate_metadata, strict_json
from ouc_desktop.project_info import AUTHOR_EMAIL, PROJECT_URL, RELEASES_URL, contact_url
from ouc_desktop.request_policy import RequestPolicy, RequestClient, RequestFailure


class Releases(TempTest):
    def release(self, version='9.0.0'):
        filename=f'OUNet-{version}-Setup-x64.exe'
        url=f'{PROJECT_URL}/releases/download/windows-v{version}/{filename}'
        return {'tag_name':'windows-v'+version,'assets':[{'name':'windows-update.json','id':1},
                {'name':filename,'browser_download_url':url,'digest':'sha256:'+'a'*64}]}
    def metadata(self, version='9.0.0'):
        return {'platform':'windows-x64','version':version,'sha256':'a'*64,
                'url':f'{PROJECT_URL}/releases/download/windows-v{version}/OUNet-{version}-Setup-x64.exe'}
    def run_lookup(self, responses):
        session=Session(responses)
        result=check_update(RequestClient(session,RequestPolicy(self.directory/'request.json')))
        return result,session
    def test_pages_past_thirty_android_releases(self):
        first=[{'tag_name':f'android-v1.0.{i}'} for i in range(30)]
        result,session=self.run_lookup([Response(json.dumps(first)),Response(json.dumps([self.release()])),Response(json.dumps(self.metadata()))])
        self.assertTrue(result['newer']);self.assertIn('page=2',session.calls[1][0]);self.assertEqual(len(session.calls),3)
    def test_exhausted_pagination_does_not_claim_current(self):
        page=json.dumps([{'tag_name':f'android-v1.0.{i}'} for i in range(30)])
        result,_=self.run_lookup([Response(page) for _ in range(10)])
        self.assertEqual(result['state'],'incomplete');self.assertNotIn('url',result)
    def test_numeric_version_order(self):
        releases=[self.release('0.3.9'),self.release('0.3.10')]
        result,_=self.run_lookup([Response(json.dumps(releases)),Response(json.dumps(self.metadata('0.3.10')))])
        self.assertEqual(result['version'],'0.3.10')
    def test_draft_prerelease_and_android_are_excluded(self):
        releases=[dict(self.release(),draft=True),dict(self.release(),prerelease=True),{'tag_name':'android-v99.0.0'}]
        result,_=self.run_lookup([Response(json.dumps(releases))]);self.assertEqual(result['state'],'empty')
    def test_missing_duplicate_or_wrong_source_installer_is_incomplete(self):
        for mode in ('missing','duplicate','source'):
            r=self.release()
            if mode=='missing':r['assets'].pop()
            elif mode=='duplicate':r['assets'].append(r['assets'][-1])
            else:r['assets'][-1]['browser_download_url']='https://example.test/setup.exe'
            with self.subTest(mode=mode),self.assertRaises(RequestFailure):self.run_lookup([Response(json.dumps([r]))])
    def test_asset_digest_conflict_is_rejected(self):
        r=self.release();r['assets'][-1]['digest']='sha256:'+'b'*64
        with self.assertRaises(RequestFailure):self.run_lookup([Response(json.dumps([r])),Response(json.dumps(self.metadata()))])
    def test_redirect_cdn_is_validated_then_metadata_is_checked(self):
        redirect=Response('',302);redirect.headers={'Location':'https://release-assets.githubusercontent.com/example/update.json?signature=fixture'}
        result,session=self.run_lookup([Response(json.dumps([self.release()])),redirect,Response(json.dumps(self.metadata()))])
        self.assertTrue(result['newer']);self.assertEqual(len(session.calls),3)
    def test_rate_limit_reports_retry_and_settles_once(self):
        response=Response('',429);response.headers={'Retry-After':'120'}
        policy=RequestPolicy(self.directory/'rate.json');session=Session([response])
        with self.assertRaisesRegex(RequestFailure,'120 秒'):check_update(RequestClient(session,policy))
        self.assertEqual(policy.scope('update').failures,1);self.assertEqual(policy.failures,0);self.assertEqual(policy.stats.total,0)
    def test_duplicate_json_keys_rejected(self):
        with self.assertRaises(ValueError):strict_json('{"version":"1.0.0","version":"9.0.0"}')
    def test_equal_and_ahead_versions_do_not_offer_downgrade(self):
        self.assertEqual(validate_metadata(self.metadata(__version__))['state'],'current')
        self.assertEqual(validate_metadata(self.metadata('0.0.1'))['state'],'ahead')
    def test_contact_uri_encodes_subject_and_contains_no_accounts(self):
        uri=urlsplit(contact_url(__version__));query=parse_qs(uri.query)
        self.assertEqual(uri.path,AUTHOR_EMAIL);self.assertEqual(query['subject'],['OUNet 反馈 - Windows '+__version__])
        self.assertEqual(PROJECT_URL,'https://github.com/JieShenAI/OUNet');self.assertEqual(RELEASES_URL,PROJECT_URL+'/releases')
