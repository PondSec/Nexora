#!/usr/bin/env python3
"""Record exact tested source/artifact hashes. Does not package secrets or production data."""
import hashlib,json,pathlib,subprocess,sys,xml.etree.ElementTree as ET
root=pathlib.Path(__file__).resolve().parent.parent
build=pathlib.Path(sys.argv[1]).resolve()
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
files=[root/'pom.xml']
for directory in ['source','tests/paper-smoke']:
    files += [p for p in (root/directory).rglob('*') if p.is_file() and 'target' not in p.parts and (p.name=='pom.xml' or 'src' in p.parts)]
sources={str(p.relative_to(root)):digest(p) for p in sorted(files)}
artifacts={str(p.relative_to(build)):digest(p) for p in sorted(build.glob('*/*.jar')) if not p.name.startswith('original-')}
totals={key:0 for key in ['tests','failures','errors','skipped']}
for report in build.glob('*/surefire-reports/TEST-*.xml'):
    suite=ET.parse(report).getroot()
    for key in totals:totals[key]+=int(suite.get(key,'0'))
if not totals['tests'] or totals['failures'] or totals['errors']:raise SystemExit('No passing test evidence')
print(json.dumps({'git_base':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'source_tree_sha256':hashlib.sha256(json.dumps(sources,sort_keys=True).encode()).hexdigest(),'source_files':sources,'artifact_sha256':artifacts,'surefire':totals,'production_deployed':False},indent=2))
