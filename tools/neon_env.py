"""Fetch an already-authenticated branch URL without displaying credentials."""
import argparse
import json
import subprocess
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('--branch', required=True)
parser.add_argument('--output', required=True)
args=parser.parse_args()
root=Path(__file__).resolve().parent.parent
destination=(root/args.output).resolve()
if not destination.is_relative_to(root):
    raise SystemExit('Output must stay in the Neko workspace')
result=subprocess.run(['neon.cmd','connection-string',args.branch,'--project-id','tiny-firefly-90266647','--no-color','-o','json'],capture_output=True,text=True,cwd=root)
if result.returncode:
    raise SystemExit('Could not retrieve branch connection; sign in with neon login')
try:
    data=json.loads(result.stdout)
except json.JSONDecodeError:
    import re
    match=re.search(r'postgres(?:ql)?://[^\s"\x1b]+',result.stdout)
    data=match.group(0) if match else None
def find_url(value):
    if isinstance(value,str) and value.startswith(('postgres://','postgresql://')): return value
    for item in (value.values() if isinstance(value,dict) else value if isinstance(value,list) else []):
        found=find_url(item)
        if found: return found
url=find_url(data)
if not url: raise SystemExit('No database URL returned')
destination.write_text('DATABASE_URL='+url+'\nDATABASE_URL_UNPOOLED='+url+'\n',encoding='utf-8')
print('Saved branch connection to '+args.output+'; credentials were not displayed')
