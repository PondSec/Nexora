#!/usr/bin/env python3
"""Reject sync-conflicted class paths, duplicate entries, and incompatible own bytecode."""
import argparse, collections, json, pathlib, re, struct, zipfile
p=argparse.ArgumentParser();p.add_argument('jars',nargs='+',type=pathlib.Path);args=p.parse_args()
result=[]
for path in args.jars:
    with zipfile.ZipFile(path) as jar:
        names=jar.namelist()
        duplicates=[name for name,count in collections.Counter(names).items() if count>1]
        bad=[name for name in names if name.endswith('.class') and re.search(r'\s',name)]
        own=[name for name in names if name.startswith('network/nexora/') and not name.startswith('network/nexora/lib/') and name.endswith('.class')]
        versions=[struct.unpack('>H',jar.read(name)[6:8])[0] for name in own]
        if duplicates or bad or max(versions,default=0)>65:
            raise SystemExit(f'Invalid artifact {path}: duplicate={duplicates}, conflicted={bad}, bytecode={max(versions,default=0)}')
        if not own:raise SystemExit(f'No Nexora classes: {path}')
        result.append({'jar':str(path),'own_classes':len(own),'maximum_java_class_version':max(versions)})
print(json.dumps(result,indent=2))
