"""Isolated fake evidence tests. These fixtures are never release/hardware evidence."""
from pathlib import Path
import hashlib,json,tempfile,unittest,zipfile
from unittest.mock import patch
import check_render_release as gate

class ReleaseGateTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.build=self.root/'build';self.build.mkdir()
        self.patch=patch.multiple(gate,ROOT=self.root,BUILD=self.build);self.patch.start()
        def write(name,value):
            p=self.root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(value),'utf8');return p
        self.write=write
        for name in ('runtime.java','core.jar','validator.exe','shader.frag'):(self.root/name).write_text('#version 430\nvoid main(){}','utf8')
        self.mod=self.root/'mod';catalog=self.mod/'42/media/aimoh_gf2/characters.json';catalog.parent.mkdir(parents=True);catalog.write_text('{"characters":{"models":[{}]}}')
        self.cfg=patch.object(gate,'settings',return_value=dict(asset_root=str(self.mod),reference_jar=str(self.root/'core.jar')));self.cfg.start()
        jar=self.root/'support.jar';payload=b'isolated-test-payload'
        with zipfile.ZipFile(jar,'w')as z:z.writestr('aimoh/gf2/bridge/Aimoh-Native-GF2.zip',payload)
        self.jar_hash=gate.sha(jar);self.pack_hash=hashlib.sha256(payload).hexdigest()
        self.write('build/render-support/build.json',dict(jar=str(jar),jar_sha256=self.jar_hash,shader_sha256=self.pack_hash,version='TEST',source_hashes={'runtime.java':gate.sha(self.root/'runtime.java')}))
        self.write('provenance/render-support.json',dict(official_host_sha256='test-host',compatible_official_hosts={'test':'test-host'}))
        self.startup=dict(jar_sha256=self.jar_hash,shader_sha256=self.pack_hash,**{k:True for k in ('normal_zombiebuddy_dispatch','native_discovered','player_custom_pack_preserved','official_host_unchanged','workshop_validator_passed')});self.write('startup.json',self.startup)
        self.reference=dict(binary=str(self.root/'validator.exe'),binary_sha256=gate.sha(self.root/'validator.exe'),all_reference_passed=True,programs=[dict(reference_status='REFERENCE_COMPILE_PASS',stages=[dict(path=str(self.root/'shader.frag'),sha256=gate.sha(self.root/'shader.frag'))])]);self.write('reference.json',self.reference)
        self.driver=dict(addon_sha256=self.jar_hash,core_sha256=gate.sha(self.root/'core.jar'),host_sha256='test-host',gpu='AMD isolated unit test',gl_messages=[],original_renderer={'frames':1,'original_stage_draws':1},native_catalog_variant_proof={'models':1,'unique_linked_pairs':1,'material_upload_readback_equal':True});self.write('amd.json',self.driver)
        self.evidence=dict(format=1,jar_sha256=self.jar_hash,shader_sha256=self.pack_hash,catalog_sha256=gate.sha(catalog),character_jar_sha256=gate.sha(self.root/'core.jar'),startup_report='startup.json',reference_reports=['reference.json'],driver_reports=['amd.json'],changed_models=[],integration_reports=[])
    def tearDown(self):
        self.cfg.stop();self.patch.stop();self.temp.cleanup()
    def result(self):
        return gate.evaluate(self.write('evidence.json',self.evidence))
    def test_amd_alone_is_limited(self):
        r=self.result();self.assertEqual(r['status'],'LIMITED_HARDWARE_VALIDATION');self.assertEqual(r['missing_vendors'],['Intel','NVIDIA'])
    def test_stale_jar_is_blocked(self):
        self.evidence['jar_sha256']='old';self.assertEqual(self.result()['status'],'BLOCKED')
    def test_modified_expanded_source_is_blocked(self):
        (self.root/'shader.frag').write_text('changed');self.assertEqual(self.result()['status'],'BLOCKED')
    def test_driver_error_is_blocked(self):
        self.driver['gl_messages']=['compile failed'];self.write('amd.json',self.driver);self.assertEqual(self.result()['status'],'BLOCKED')
    def test_missing_new_character_is_blocked(self):
        self.evidence['changed_models']=['new-model'];self.assertEqual(self.result()['status'],'BLOCKED')
    def test_reference_failure_is_blocked(self):
        self.reference['all_reference_passed']=False;self.write('reference.json',self.reference);self.assertEqual(self.result()['status'],'BLOCKED')
    def test_write_only_access_is_checked_even_when_reference_compiles(self):
        shader=self.root/'shader.frag'
        header='#version 430\nlayout(std430,binding=0) writeonly buffer Output { vec4 mainLight; };\n'
        for body,status in (
            ('mainLight=vec4(1.0);','LIMITED_HARDWARE_VALIDATION'),
            ('mainLight.rgb=vec3(mainLight.r);','BLOCKED'),
            ('mainLight.rgb*=0.5;','BLOCKED'),
        ):
            with self.subTest(body=body):
                shader.write_text(header+'void main(){'+body+'}',encoding='utf8')
                self.reference['programs'][0]['stages'][0]['sha256']=gate.sha(shader)
                self.write('reference.json',self.reference)
                self.assertEqual(self.result()['status'],status)
    def test_missing_host_is_blocked(self):
        self.write('provenance/render-support.json',dict(official_host_sha256='test-host',compatible_official_hosts={'one':'test-host','two':'missing-host'}));self.assertEqual(self.result()['status'],'BLOCKED')
    def test_incomplete_catalog_validation_is_blocked(self):
        self.driver.pop('native_catalog_variant_proof');self.write('amd.json',self.driver);self.assertEqual(self.result()['status'],'BLOCKED')
    def test_combined_requires_both_packs_and_matching_reference_backend(self):
        build=gate.read(self.root/'build/render-support/build.json')
        build['backends']={'native':{'shader_sha256':self.pack_hash},'eclipse':{'shader_sha256':'eclipse-pack'}}
        self.write('build/combined-support/build.json',build)
        self.evidence['distribution']='combined'
        self.startup['shader_hashes']={'native':self.pack_hash,'eclipse':'eclipse-pack'}
        self.reference.update(backend='eclipse',jar_sha256=self.jar_hash,shader_sha256=self.pack_hash,host_sha256='test-host')
        self.write('startup.json',self.startup);self.write('reference.json',self.reference)
        self.assertEqual(self.result()['status'],'BLOCKED')
        self.startup['eclipse_discovered']=True
        self.write('startup.json',self.startup)
        self.assertEqual(self.result()['status'],'BLOCKED')
        self.reference['backend']='native'
        self.write('reference.json',self.reference)
        self.assertEqual(self.result()['status'],'LIMITED_HARDWARE_VALIDATION')

if __name__=='__main__':unittest.main()
