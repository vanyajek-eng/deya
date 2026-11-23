import base64, pathlib, sys
groups={}
for line in pathlib.Path(sys.argv[1]).read_text().splitlines():
    parts=line.strip().split("|",3)
    if len(parts)!=4 or not parts[0].endswith(".png"):
        continue
    name,index,total,value=parts
    groups.setdefault(name,{"total":int(total),"parts":{}})["parts"][int(index)]=value
out=pathlib.Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True)
for name,item in groups.items():
    if len(item["parts"])!=item["total"]:
        raise RuntimeError("Incomplete UI image: "+name)
    raw="".join(item["parts"][i] for i in range(item["total"]))
    (out/name).write_bytes(base64.b64decode(raw))
if len(groups)!=4:
    raise RuntimeError("Expected four UI images, got "+str(len(groups)))
print("Verified four UI images")
