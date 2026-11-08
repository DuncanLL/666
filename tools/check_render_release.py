"""Check build-bound rendering evidence; --public also requires all three GPU vendors."""
from pathlib import Path
import argparse, hashlib, json, re, zipfile
from common import ROOT, BUILD, sha, save, settings

def read(path):
    return json.loads(Path(path).read_text('utf-8-sig'))

def evaluate(evidence_file):
    evidence_file=Path(evidence_file).resolve();base=evidence_file.parent;e=read(evidence_file)
    build=read(BUILD/'render-support/build.json');origin=read(ROOT/'provenance/render-support.json')
    errors=[];vendors=set();hosts=set()
    def require(ok,message):
        if not ok:errors.append(message)
    def report(value):
        p=Path(value);return read(p if p.is_absolute() else base/p)
    require(e.get('format')==1,'Unsupported evidence format')
    require(sha(build['jar'])==build['jar_sha256'],'Built JAR has changed')
    with zipfile.ZipFile(build['jar'])as archive:
        payload=archive.read('aimoh/gf2/bridge/Aimoh-Native-GF2.zip')
        require(hashlib.sha256(payload).hexdigest()==build['shader_sha256'],'Embedded shader payload differs')
    for name,digest in build['source_hashes'].items():require(sha(ROOT/name)==digest,'Source changed after build: '+name)
    for key in ('jar_sha256','shader_sha256'):require(e.get(key)==build[key],'Evidence belongs to another '+key)
    catalog=Path(settings()['asset_root'])/'42/media/aimoh_gf2/characters.json'
    require(e.get('catalog_sha256')==sha(catalog),'Character catalog changed after verification')
    model_count=len(read(catalog)['characters']['models']);require(model_count>0,'Character catalog is empty')
    character_jar=Path(settings()['reference_jar'])
    require(e.get('character_jar_sha256')==sha(character_jar),'Character core changed after verification')
    allowed=set(origin.get('compatible_official_hosts',{}).values())or{origin['official_host_sha256']}
    startup=report(e['startup_report'])
    require(startup.get('jar_sha256')==build['jar_sha256']and startup.get('shader_sha256')==build['shader_sha256'],'Startup evidence is stale')
    for flag in ('normal_zombiebuddy_dispatch','native_discovered','player_custom_pack_preserved','official_host_unchanged','workshop_validator_passed'):require(startup.get(flag)is True,'Startup check not passed: '+flag)
    references=e.get('reference_reports',[]);require(bool(references),'No host-expanded reference compilation evidence')
    for path in references:
        ref=report(path);rows=ref.get('programs',[])
        require(sha(ref['binary'])==ref['binary_sha256'],'Reference compiler identity changed')
        require(bool(rows)and ref.get('all_reference_passed')is True,'Reference compilation incomplete: '+path)
        for row in rows:
            require(row.get('reference_status')=='REFERENCE_COMPILE_PASS','Reference stage/link failure')
            for stage in row.get('stages',[]):
                p=Path(stage['path']);require(sha(p)==stage['sha256'],'Expanded source changed: '+str(p))
                text=p.read_text('utf8');require(text.lstrip().startswith('#version')and not re.search(r'^\s*#include\b',text,re.M),'Raw/unversioned shader is not host-expanded evidence')
    drivers=e.get('driver_reports',[]);require(bool(drivers),'No actual driver rendering evidence')
    coverage=False
    for path in drivers:
        d=report(path);gpu=d.get('gpu','');host=d.get('host_sha256')
        require(d.get('addon_sha256')==build['jar_sha256'],'Driver report belongs to another JAR: '+path)
        require(d.get('core_sha256')==sha(character_jar),'Driver report used another character core')
        require(host in allowed,'Driver report used an unverified VP host')
        require(d.get('gl_messages') == [] and d.get('gl_error',0) == 0 and d.get('iris_active',False) is False,'Driver reported GL errors or wrong backend')
        renderer=d.get('original_renderer',{})
        require(renderer.get('frames',0)>0 and renderer.get('original_stage_draws',0)>0,'Driver report did not render the character path')
        variants=d.get('native_catalog_variant_proof',{})
        coverage |= variants.get('models',0)>=model_count and variants.get('unique_linked_pairs',0)>0 and variants.get('material_upload_readback_equal') is True
        if re.search(r'AMD|Radeon',gpu,re.I):vendors.add('AMD')
        elif re.search(r'NVIDIA|GeForce|Quadro',gpu,re.I):vendors.add('NVIDIA')
        elif re.search(r'Intel',gpu,re.I):vendors.add('Intel')
        else:require(False,'Unidentified real driver: '+gpu)
        hosts.add(host)
    require(allowed<=hosts,'Not all declared VP versions have actual driver evidence')
    require(coverage,'No complete current-catalog program/material validation')
    changed=set(e.get('changed_models',[]));covered=set()
    for path in e.get('integration_reports',[]):
        d=report(path);require(d.get('status')=='CONTRACTS_OK_GAMEPLAY_UNVERIFIED'and d.get('errors',[])==[],'Character integration failed')
        covered.update(d.get('targets',[]))
    require(changed<=covered,'Changed/new character lacks integration evidence')
    missing=sorted({'AMD','NVIDIA','Intel'}-vendors)
    status='BLOCKED'if errors else 'LIMITED_HARDWARE_VALIDATION'if missing else 'THREE_VENDOR_DRIVER_VALIDATED'
    return dict(status=status,version=build['version'],jar_sha256=build['jar_sha256'],shader_sha256=build['shader_sha256'],errors=errors,driver_vendors=sorted(vendors),missing_vendors=missing,
                scope='Actual documented devices/drivers only; reference compilation is not vendor hardware. Gameplay, multiplayer and cold subscription acceptance remain separate.',evidence=str(evidence_file))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--evidence',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--public',action='store_true');a=p.parse_args()
    try:r=evaluate(a.evidence)
    except (OSError,KeyError,ValueError,TypeError,zipfile.BadZipFile)as exc:r=dict(status='BLOCKED',errors=[str(exc)])
    save(a.out,r);print(json.dumps(r,ensure_ascii=False))
    raise SystemExit(1 if r['status']=='BLOCKED'or a.public and r['status']!='THREE_VENDOR_DRIVER_VALIDATED'else 0)
