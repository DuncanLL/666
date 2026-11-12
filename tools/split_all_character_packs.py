"""Package every character and its outfits under one optional Workshop MOD."""
from pathlib import Path
from collections import Counter
import argparse
import json
import re
import shutil
import zipfile

from common import save, sha
from split_r116_character_packs import catalog_subset, material_ids, resources, ui_files

NEW_PACKS = {
    "莱娅": ("R116Laiya", "Laiya"),
    "夏安": ("R116Cheyanne", "Cheyanne"),
    "阿斯缇亚": ("R116Asteria", "Asteria"),
    "克莱妲": ("R116Koleda", "Klaid"),
    "妮基塔": ("R116Nikketa", "Nikketa"),
    "芙洛伦": ("R116Florence", "Florence"),
}
MEDIA = Path("42/media")
REGISTRY = MEDIA / "aimoh_pmx/registry.json"
GF2 = MEDIA / "aimoh_gf2/characters.json"
ROOM = MEDIA / "ui/AimohPMXReadyRoom/catalog.json"
JAR = MEDIA / "java/client/AimohPMXPreview.jar"

def read(path):
    return json.loads(path.read_text("utf-8-sig"))

def copy(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    if source.is_dir():
        shutil.copytree(source, target, dirs_exist_ok=True)
    else:
        shutil.copy2(source, target)

def mod_root(workshop, project):
    return workshop / project / "Contents/mods" / ("AimohPmxPreview" if project == "R116" else project)

def update_info(path, version, name=None):
    text = path.read_text("utf-8-sig")
    text = re.sub(r"(?m)^modversion=.*$", "modversion=" + version, text)
    if name:
        text = re.sub(r"(?m)^name=.*$", "name=R116 - " + name, text)
    path.write_text(text, "utf8")

def build(workshop, output, version):
    assert not output.exists(), "Use a fresh output directory."
    source = mod_root(workshop, "R116")
    registry, catalog, room = read(source/REGISTRY), read(source/GF2), read(source/ROOM)
    full = catalog["characters"]
    shader_models = {m["pmx_sha256"]: m for m in full["models"]}
    grouped = {role: [] for role in NEW_PACKS}
    for row in registry["models"]:
        role = room["models"].get(row["id"], {}).get("character") or row["label"].split(" / ")[0]
        grouped[role].append(row)
    assert all(grouped.values())
    shards = {}
    model_dirs = {}
    for role, rows in grouped.items():
        mod, _ = NEW_PACKS[role]
        models = []
        model_dirs[mod] = set()
        for row in rows:
            profile = read(source / MEDIA / "aimoh_pmx" / row["profile"])
            fingerprint = profile["pmx_sha256"]
            assert sha(source / MEDIA / "aimoh_pmx" / row["model"]) == fingerprint
            model_dirs[mod].add(MEDIA/"aimoh_pmx"/Path(row["model"]).parent)
            if fingerprint in shader_models:
                models.append(shader_models[fingerprint])
            else:
                assert row.get("hidden"), "Visible character lacks rendering data"
        shards[mod] = catalog_subset(full, models, material_ids(models))
    moved = {m["pmx_sha256"] for shard in shards.values() for m in shard["models"]}
    residual = [m for m in full["models"] if m["pmx_sha256"] not in moved]
    unused_materials = set(range(len(full["materials"]))) - material_ids(full["models"])
    retained = catalog_subset(full, residual, material_ids(residual) | unused_materials, True)
    core_data = resources(retained)
    for mod in registry["packs"]:
        fragment = read(mod_root(workshop,mod)/MEDIA/"aimoh_gf2/packs"/(mod+".json"))
        for resource in resources(fragment):
            if not (mod_root(workshop,mod)/resource).exists():
                assert (source/resource).is_file(), resource
                core_data.add(resource)
    usage = Counter(path for shard in shards.values() for path in resources(shard))
    core_data.update(path for path,count in usage.items() if count>1)
    exclude = set()
    summaries = []
    optional = []
    for role, rows in grouped.items():
        mod, voice = NEW_PACKS[role]
        target = mod_root(output, mod)
        project = output/mod
        (target/"common").mkdir(parents=True)
        (target/"42").mkdir()
        (target/"42/mod.info").write_text(
            f"name=R116 - {role}\nid={mod}\nrequire=\\AimohPmxPreview\nmodversion={version}\nversionMin=42.22\nversionMax=42.22\n",
            "utf8")
        (project/"workshop.txt").write_text(
            f"version=1\ntitle=R116 - {role}\ndescription=\u200b\ntags=Build 42\nvisibility=unlisted\n", "utf8")
        copy(workshop/"R116/preview.png",project/"preview.png")
        registry["packs"][mod] = [r["id"] for r in rows]
        entries = {}
        for row in rows:
            if row["id"] in room["models"]:
                entries[row["id"]] = room["models"][row["id"]].copy()
                placeholder = {k:entries[row["id"]][k] for k in ("character","skin","officialSkin","isDefault","portrait") if k in entries[row["id"]]}
                room["models"][row["id"]] = {**placeholder, "pack":mod}
                optional.append(row["id"])
        for directory in model_dirs[mod]:
            copy(source/directory,target/directory)
            exclude.add(directory)
        with zipfile.ZipFile(source/JAR) as archive:
            for row in rows:
                fingerprint = read(source/MEDIA/"aimoh_pmx"/row["profile"])["pmx_sha256"]
                chest = target/MEDIA/"aimoh_pmx"/Path(row["model"]).parent/"chest.bin"
                embedded = "aimoh/pmxpreview/chest/"+fingerprint+".bin"
                if not chest.is_file() and embedded in archive.namelist():
                    chest.write_bytes(archive.read(embedded))
        save(target/MEDIA/"aimoh_pmx/packs"/(mod+".json"),dict(format=1,mod=mod,models=rows))
        save(target/MEDIA/"aimoh_gf2/packs"/(mod+".json"),dict(format=1,mod=mod,characters=shards[mod]))
        save(target/MEDIA/"ui/AimohPMXReadyRoom/packs"/(mod+".json"),dict(models=entries,labels={}))
        for relative in resources(shards[mod]) - core_data:
            copy(source/relative,target/relative)
            exclude.add(relative)
        for relative in ui_files(entries):
            if relative in {Path("42")/meta["portrait"] for meta in entries.values() if "portrait" in meta}:
                continue
            copy(source/relative,target/relative)
        voice_paths = [
            MEDIA/"lua/client"/f"ZZ_AimohPMX{voice}Voice.lua",
            MEDIA/"lua/shared"/f"AimohPMX{voice}Voice",
            MEDIA/"scripts"/f"AimohPMX{voice}Voice_Sounds.txt",
            MEDIA/"scripts"/f"AimohPMX{voice}NativeVoice_Sounds.txt",
            MEDIA/"sound"/f"AimohPMX{voice}",
        ]
        for relative in voice_paths:
            if (source/relative).exists():
                copy(source/relative,target/relative)
                exclude.add(relative)
        summaries.append(dict(mod=mod,character=role,skins=len(entries),models=len(rows),new_project=True))
    room["optionalModels"] = optional + room["optionalModels"]
    assert len(room["optionalModels"]) == len(set(room["optionalModels"])) == 39
    registry["models"] = []
    known_models = {model for ids in registry["packs"].values() for model in ids}
    for directory in (source/MEDIA/"aimoh_pmx/models").iterdir():
        relative = directory.relative_to(source)
        if directory.is_dir() and relative not in exclude:
            assert directory.name in known_models, "Unexpected unregistered model directory"
            exclude.add(relative)
    for mod in sorted(set(registry["packs"]) - set(shards)):
        source_pack = mod_root(workshop,mod)
        ui = read(source_pack/MEDIA/"ui/AimohPMXReadyRoom/packs"/(mod+".json"))
        roles = {m["character"] for m in ui["models"].values()}
        assert len(roles)==1
        role = next(iter(roles))
        copy(workshop/mod,output/mod)
        target=mod_root(output,mod)
        update_info(target/"42/mod.info",version,role)
        metadata=(output/mod/"workshop.txt").read_text("utf-8-sig")
        metadata=re.sub(r"(?m)^title=.*$","title=R116 - "+role,metadata)
        (output/mod/"workshop.txt").write_text(metadata,"utf8")
        summaries.append(dict(mod=mod,character=role,skins=len(ui["models"]),
                              models=len(read(target/MEDIA/"aimoh_pmx/packs"/(mod+".json"))["models"]),new_project=False))
    # Portraits and small shared UI assets remain available for unsubscribed character hints.
    excluded_paths = {Path("Contents/mods/AimohPmxPreview")/p for p in exclude}
    def ignore(directory,names):
        relative = Path(directory).relative_to(workshop/"R116")
        return [n for n in names if relative/n in excluded_paths]
    shutil.copytree(workshop/"R116",output/"R116",ignore=ignore)
    target=mod_root(output,"R116")
    save(target/REGISTRY,registry)
    save(target/GF2,{**catalog,"characters":retained})
    save(target/ROOM,room)
    update_info(target/"42/mod.info",version)
    assert sha(source/JAR)==sha(target/JAR)
    for summary in summaries:
        root=output/summary["mod"]
        files=[p for p in root.rglob("*") if p.is_file()]
        summary["bytes"]=sum(p.stat().st_size for p in files)
        summary["files"]=len(files)
    core_files=[p for p in (output/"R116").rglob("*") if p.is_file()]
    save(output.parent/"split-report.json",dict(version=version,characters=summaries,
        core_bytes=sum(p.stat().st_size for p in core_files),core_files=len(core_files),
        core_shared_resources=len(core_data),core_compatibility_shader_models=len(residual),
        runtime_sha256=sha(target/JAR),removed_from_core=[p.as_posix() for p in sorted(exclude)]))
    print("Prepared",len(summaries),"character projects and R116 core.",flush=True)

if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workshop",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--version",required=True)
    args=parser.parse_args()
    build(args.workshop.resolve(),args.output.resolve(),args.version)
