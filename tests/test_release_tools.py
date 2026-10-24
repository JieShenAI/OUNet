"""Reject corrupt and mixed release assets before any remote write."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
import release_bundle as bundle
from publish_release import upload_draft, release_edit


class ReleaseBundleTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(dir=ROOT/'tests')
        self.root=Path(self.temp.name)
        self.source=self.root/'desktop/dist';self.source.mkdir(parents=True)
        self.version='0.3.1'
        for name in (f'OUNet-{self.version}-Setup-x64.exe',f'OUNet-{self.version}-Windows-x64-portable.zip'):
            path=self.source/name;path.write_bytes(b'synthetic public package')
            path.with_name(name+'.sha256').write_text(bundle.digest(path)+'  '+name+'\n',encoding='ascii')
        setup=self.source/f'OUNet-{self.version}-Setup-x64.exe'
        meta={'platform':'windows-x64','version':self.version,'url':f'{bundle.REPO}/releases/download/windows-v{self.version}/{setup.name}',
              'sha256':bundle.digest(setup)}
        (self.source/'windows-update.json').write_text(json.dumps(meta),encoding='utf-8')
        (self.source/'WINDOWS_RELEASE_NOTES.md').write_text('Synthetic release notes',encoding='utf-8')
    def tearDown(self):self.temp.cleanup()
    def stage(self):
        with patch.object(bundle,'ROOT',self.root),patch.object(bundle,'source_state',return_value=('a'*40,False)):
            return bundle.stage('windows',self.version,True)
    def test_exact_current_assets_are_staged_without_old_dist_files(self):
        (self.source/'old-setup.exe').write_bytes(b'not part of this release')
        target=self.stage();manifest=bundle.verify(target)
        self.assertNotIn('old-setup.exe',[p.name for p in target.iterdir()]);self.assertFalse(manifest['sourceDirty'])
    def test_checksum_mismatch_fails_before_staging(self):
        (self.source/f'OUNet-{self.version}-Setup-x64.exe').write_bytes(b'corrupted')
        with self.assertRaisesRegex(ValueError,'Checksum'):self.stage()
    def test_metadata_version_and_source_are_bound(self):
        path=self.source/'windows-update.json';meta=json.loads(path.read_text());meta['url']='https://example.test/setup.exe'
        path.write_text(json.dumps(meta))
        with self.assertRaisesRegex(ValueError,'metadata'):self.stage()
    def test_bundle_manifest_detects_tampering_and_extra_files(self):
        target=self.stage();(target/'windows-update.json').write_text('{}')
        with self.assertRaises(ValueError):bundle.verify(target)
        target=self.stage();(target/'credentials.txt').write_text('synthetic')
        with self.assertRaisesRegex(ValueError,'Unexpected'):bundle.verify(target)
    def test_dirty_or_unchecked_build_cannot_create_draft(self):
        target=self.stage();path=target/'release-manifest.json';meta=json.loads(path.read_text());meta['sourceDirty']=True;path.write_text(json.dumps(meta))
        with self.assertRaisesRegex(ValueError,'clean source'):upload_draft(None,target)
    def test_tag_points_to_exact_source_before_release_creation(self):
        target=self.stage()
        class WrongTag:
            def request(self,path):
                if path.startswith('/commits/'):return {'sha':'a'*40}
                return {'object':{'type':'commit','sha':'b'*40}}
        with self.assertRaisesRegex(ValueError,'another source'):upload_draft(WrongTag(),target)

    def test_every_draft_edit_preserves_uncreated_tag_and_frozen_commit(self):
        manifest={'tag':'windows-v0.3.1','sourceCommit':'a'*40}
        for changes in ({'body':'updated notes'},{'draft':False,'make_latest':'false'},{'prerelease':True}):
            result=release_edit(manifest,**changes)
            self.assertEqual(result['tag_name'],manifest['tag'])
            self.assertEqual(result['target_commitish'],manifest['sourceCommit'])
            self.assertEqual({k:result[k] for k in changes},changes)


class WindowsSecurityBundleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT/'tests')
        self.directory = Path(self.temp.name)
        self.version = '0.3.6'
        packages = [f'OUNet-{self.version}-Setup-x64.exe', f'OUNet-{self.version}-Windows-x64-portable.zip']
        results = []
        for name in packages:
            path = self.directory/name
            path.write_bytes(b'synthetic package for security-gate regression')
            (self.directory/(name+'.sha256')).write_text(bundle.digest(path)+'  '+name+'\n')
            results.append({'target': {'kind': 'file', 'name': name, 'size': path.stat().st_size, 'sha256': bundle.digest(path)},
                            'exitCode': 0, 'unchangedDuringScan': True, 'noThreatsDetected': True})
        results.append({'target': {'kind': 'directory', 'name': 'OUNet', 'fileCount': 3, 'treeSha256': 'a'*64},
                        'exitCode': 0, 'unchangedDuringScan': True, 'noThreatsDetected': True})
        (self.directory/'windows-update.json').write_text(json.dumps({'platform': 'windows-x64', 'version': self.version,
            'url': f'{bundle.REPO}/releases/download/windows-v{self.version}/{packages[0]}', 'sha256': bundle.digest(self.directory/packages[0])}))
        (self.directory/'WINDOWS_RELEASE_NOTES.md').write_text('Synthetic security gate fixture')
        self.report = {'schemaVersion': 1, 'version': self.version, 'allTargetsClean': True, 'realTimeProtectionEnabled': True,
            'archivesScanned': True, 'exclusionsIgnored': True, 'engineVersion': 'synthetic', 'signatureVersion': 'synthetic',
            'scannedAtUtc': '2026-10-24T00:00:00Z', 'results': results}
        self.write_report()
    def tearDown(self):
        self.temp.cleanup()
    def write_report(self):
        (self.directory/'windows-security-scan.json').write_text(json.dumps(self.report), encoding='utf-8-sig')
    def test_current_windows_release_requires_successful_exact_scan(self):
        self.assertIn('windows-security-scan.json', bundle.validate_files(self.directory, 'windows', self.version))
        self.report['results'][0]['noThreatsDetected'] = False
        self.write_report()
        with self.assertRaisesRegex(ValueError, 'security scan'):
            bundle.validate_files(self.directory, 'windows', self.version)
    def test_a_clean_report_for_another_installer_cannot_authorize_publication(self):
        self.report['results'][0]['target']['sha256'] = 'b'*64
        self.write_report()
        with self.assertRaisesRegex(ValueError, 'another package'):
            bundle.validate_files(self.directory, 'windows', self.version)
    def test_payload_or_archive_cannot_be_omitted_from_scan(self):
        self.report['results'].pop()
        self.write_report()
        with self.assertRaisesRegex(ValueError, 'target list'):
            bundle.validate_files(self.directory, 'windows', self.version)


if __name__=='__main__':unittest.main()
