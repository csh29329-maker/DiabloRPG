import json, os, sys, zipfile, hashlib
from PIL import Image

RUNES = ["El","Eld","Tir","Nef","Eth","Ith","Tal","Ral","Ort","Thul","Amn","Sol","Shael","Dol","Hel","Io","Lum","Ko","Fal","Lem","Pul","Um","Mal","Ist","Gul","Vex","Ohm","Lo","Sur","Ber","Jah","Cham","Zod"]
assert len(RUNES) == 33
out_dir = sys.argv[1]

def tier(i):
    if i <= 8:  return (205, 205, 205)   # 낮은 룬: 은회색
    if i <= 18: return (90, 200, 230)    # 중간: 하늘색
    if i <= 28: return (235, 190, 60)    # 높은 룬: 금색
    return (225, 70, 60)                 # 최상위: 붉은색

def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)

def make(i):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    base = tier(i)
    # 마름모꼴 보석 (중심 7.5, 반지름 7)
    for y in range(16):
        for x in range(16):
            d = abs(x - 7.5) + abs(y - 7.5)
            if d <= 7.5:
                px[x, y] = shade(base, 0.55) + (255,)
            if 6.0 < d <= 7.5:
                px[x, y] = shade(base, 1.0) + (255,)
    # 번호를 6비트 이진 점으로 표시: 1이면 밝게, 0이면 어둡게
    for k in range(6):
        bit = (i >> (5 - k)) & 1
        x = 2 + k * 2
        col = (255, 255, 255, 255) if bit else (40, 30, 50, 255)
        for y in (7, 8):
            px[x, y] = col
    return img

os.makedirs(out_dir, exist_ok=True)
zpath = os.path.join(out_dir, "DiabloRPG-Runes-ResourcePack.zip")
overrides = []
with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("pack.mcmeta", json.dumps({"pack": {"pack_format": 34, "description": "DiabloRPG rune icons (1.21.1)"}}, ensure_ascii=False))
    for i, name in enumerate(RUNES, start=1):
        key = "rune_%02d" % i
        tmp = os.path.join(out_dir, key + ".png")
        make(i).save(tmp)
        with open(tmp, "rb") as f:
            z.writestr("assets/diablorpg/textures/item/rune/%s.png" % key, f.read())
        os.remove(tmp)
        z.writestr("assets/diablorpg/models/item/rune/%s.json" % key, json.dumps({
            "parent": "minecraft:item/generated",
            "textures": {"layer0": "diablorpg:item/rune/%s" % key}}))
        overrides.append({"predicate": {"custom_model_data": i}, "model": "diablorpg:item/rune/%s" % key})
    z.writestr("assets/minecraft/models/item/amethyst_shard.json", json.dumps({
        "parent": "minecraft:item/generated",
        "textures": {"layer0": "minecraft:item/amethyst_shard"},
        "overrides": overrides}, indent=1))
print(zpath, hashlib.sha1(open(zpath, "rb").read()).hexdigest())

# 확인용 미리보기 (6x6 격자, 8배 확대) - 배포 파일에는 포함하지 않는다
sheet = Image.new("RGBA", (6 * 16 * 8, 6 * 16 * 8), (60, 60, 60, 255))
for i in range(1, 34):
    tile = make(i).resize((128, 128), Image.NEAREST)
    sheet.paste(tile, (((i - 1) % 6) * 128, ((i - 1) // 6) * 128), tile)
sheet.save(os.path.join(out_dir, "..", "preview_runes.png"))
