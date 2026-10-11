import importlib.machinery
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import io

loader=importlib.machinery.SourceFileLoader('sample_setup',str(Path(__file__).resolve().parents[1]/'setup'))
spec=importlib.util.spec_from_loader(loader.name,loader);setup=importlib.util.module_from_spec(spec);loader.exec_module(setup)

class SetupTests(unittest.TestCase):
    def test_plan_shared_project_and_bucket(self):
        c=setup.DEFAULTS|{'SAMPLE_MODE':'cloud','GCP_PROJECT':'test-project-123','GCP_BUCKET':'shared-fixtures','GCP_SHARED_BUCKET':'true'}
        setup.validate(c);p=setup.plan(c)
        self.assertTrue(p['bucket_shared']);self.assertEqual(p['limits']['min_instances'],0);self.assertLessEqual(p['limits']['max_instances'],2)
        self.assertEqual(p['object_prefix'],'recommendations/sample-recs/')
        other=setup.plan(c|{'GCP_PREFIX':'other-recs'});self.assertNotEqual(p['object_prefix'],other['object_prefix'])
    def test_missing_keys_and_recursive_urls(self):
        with self.assertRaises(ValueError):setup.validate(setup.DEFAULTS,credentials=True)
        with self.assertRaises(ValueError):setup.validate(setup.DEFAULTS|{'REVIEW_SUMMARY_URL':'https://example.com/api/recommendations'})
        with self.assertRaises(ValueError):setup.validate(setup.DEFAULTS|{'CORRIDORA_OTLP_ENDPOINT':'https://key:secret@evil.example'})
    def test_owner_only_files(self):
        with tempfile.TemporaryDirectory() as d:
            setup.STATE=Path(d);path=Path(d)/'secret.json';setup.save(path,{'value':'mock'});self.assertEqual(path.stat().st_mode&0o777,0o600)
    def test_scoped_stored_parentage_query(self):
        trace='a'*32
        body={'spans':[{'kind':3,'service_name':'sample-recommendations-web','trace_id':trace,'span_id':'b'*16},{'kind':2,'service_name':'sample-recommendations-api','trace_id':trace,'span_id':'c'*16,'parent_span_id':'b'*16}]}
        c=setup.DEFAULTS|{'CORRIDORA_QUERY_BASE_URL':'https://app.corridora.example','CORRIDORA_QUERY_KEY':'mock-query'}
        with patch.object(setup.urllib.request,'urlopen',return_value=io.BytesIO(setup.json.dumps(body).encode())) as mocked:
            self.assertTrue(setup.stored_correlation(c,trace))
            self.assertEqual(mocked.call_args.args[0].get_header('X-corridora-api-key'),'mock-query')
            self.assertTrue(mocked.call_args.args[0].full_url.endswith('/web/api/v1/traces/'+trace))
    def test_teardown_preserves_foreign_bucket_prefix(self):
        import argparse
        with tempfile.TemporaryDirectory() as d:
            setup.STATE=Path(d)
            inventory={'project':'test-project-123','prefix':'sample-recs','object_prefix':'recommendations/sample-recs/','resources':[{'kind':'service-account','name':'sample-recs-run@test-project-123.iam.gserviceaccount.com','owned':True},{'kind':'bucket','name':'shared-fixtures','owned':True}]}
            setup.save(setup.STATE/'inventory.json',inventory);calls=[]
            def command(argv,*a,**kw):
                calls.append(argv)
                return 0,'[{"name":"recipes/another-app.json"},{"name":"recommendations/sample-recs/model.json"}]' if 'objects' in argv and 'list' in argv else ''
            with patch.object(setup,'run_cmd',side_effect=command):setup.destroy(setup.DEFAULTS,argparse.Namespace(plan=False,non_interactive=True))
            removals=[x for x in calls if 'storage' in x and 'rm' in x]
            self.assertEqual(len(removals),1)
            self.assertIn('gs://shared-fixtures/recommendations/sample-recs/',removals[0])
            self.assertNotIn('gs://shared-fixtures',removals[0])
    def test_running_version_comes_from_built_artifact(self):
        import hashlib
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);state=root/'.local';app=root/'target/quarkus-app/app/sample-recommendations-1.0.0.jar';app.parent.mkdir(parents=True);app.write_bytes(b'actual-built-jar')
            with patch.object(setup,'ROOT',root),patch.object(setup,'STATE',state):
                setup.save(state/'build.json',{'service_version':'built-commit-v1','app_jar_sha256':hashlib.sha256(app.read_bytes()).hexdigest()})
                with patch.object(setup,'build') as build:
                    self.assertEqual(setup.built_version(setup.DEFAULTS|{'SERVICE_VERSION':'fabricated-new-version'}),'built-commit-v1');build.assert_not_called()

if __name__=='__main__':unittest.main()
