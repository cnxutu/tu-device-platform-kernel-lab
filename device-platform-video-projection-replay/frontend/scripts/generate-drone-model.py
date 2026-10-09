"""Generate the small, self-contained glTF drone used by the replay map.

Run from the frontend directory: python scripts/generate-drone-model.py
The model uses glTF's Y-up convention; its nose points along +X so Cesium's
axis correction makes heading zero point north in the local ENU frame.
"""

import json
import math
import struct
from pathlib import Path


COLORS = {
    "shell": (0.80, 0.90, 0.94, 1.0),
    "deck": (0.19, 0.34, 0.43, 1.0),
    "arms": (0.16, 0.27, 0.35, 1.0),
    "motors": (0.08, 0.17, 0.24, 1.0),
    "rotors": (0.42, 0.82, 0.87, 1.0),
    "nose": (0.70, 0.34, 0.94, 1.0),
    "lens": (0.08, 0.18, 0.25, 1.0),
}
mesh = {name: {"positions": [], "normals": [], "indices": []} for name in COLORS}


def triangle(material, vertices, normal, vertex_normals=None):
    part = mesh[material]
    first = len(part["positions"]) // 3
    for vertex, vertex_normal in zip(vertices, vertex_normals or [normal] * 3):
        part["positions"].extend(vertex)
        part["normals"].extend(vertex_normal)
    part["indices"].extend((first, first + 1, first + 2))


def box(material, center, size):
    x, y, z = center
    dx, dy, dz = (value / 2 for value in size)
    corners = [(x + sx * dx, y + sy * dy, z + sz * dz)
               for sx in (-1, 1) for sy in (-1, 1) for sz in (-1, 1)]
    for a, b, c, d, normal in (
        (4, 6, 7, 5, (1, 0, 0)), (0, 1, 3, 2, (-1, 0, 0)),
        (2, 3, 7, 6, (0, 1, 0)), (0, 4, 5, 1, (0, -1, 0)),
        (1, 5, 7, 3, (0, 0, 1)), (0, 2, 6, 4, (0, 0, -1)),
    ):
        triangle(material, (corners[a], corners[b], corners[c]), normal)
        triangle(material, (corners[a], corners[c], corners[d]), normal)


def cylinder(material, start, end, radius, sides=20):
    axis = tuple(b - a for a, b in zip(start, end))
    length = math.sqrt(sum(v * v for v in axis))
    direction = tuple(v / length for v in axis)
    helper = (0, 1, 0) if abs(direction[1]) < 0.9 else (1, 0, 0)
    tangent = (direction[1] * helper[2] - direction[2] * helper[1],
               direction[2] * helper[0] - direction[0] * helper[2],
               direction[0] * helper[1] - direction[1] * helper[0])
    norm = math.sqrt(sum(v * v for v in tangent))
    tangent = tuple(v / norm for v in tangent)
    bitangent = (direction[1] * tangent[2] - direction[2] * tangent[1],
                 direction[2] * tangent[0] - direction[0] * tangent[2],
                 direction[0] * tangent[1] - direction[1] * tangent[0])

    def ring(point, angle):
        return tuple(point[i] + radius * (math.cos(angle) * tangent[i]
                     + math.sin(angle) * bitangent[i]) for i in range(3))

    for i in range(sides):
        a, b = (2 * math.pi * j / sides for j in (i, i + 1))
        p0, p1, q0, q1 = ring(start, a), ring(start, b), ring(end, a), ring(end, b)
        na = tuple(math.cos(a) * tangent[k] + math.sin(a) * bitangent[k] for k in range(3))
        nb = tuple(math.cos(b) * tangent[k] + math.sin(b) * bitangent[k] for k in range(3))
        triangle(material, (p0, p1, q1), na, (na, nb, nb))
        triangle(material, (p0, q1, q0), na, (na, nb, na))
        triangle(material, (start, p1, p0), tuple(-v for v in direction))
        triangle(material, (end, q0, q1), direction)


def ellipsoid(material, center, radii, bands=16, sectors=32):
    for band in range(bands):
        lat0 = -math.pi / 2 + math.pi * band / bands
        lat1 = -math.pi / 2 + math.pi * (band + 1) / bands
        for sector in range(sectors):
            lon0 = 2 * math.pi * sector / sectors
            lon1 = 2 * math.pi * (sector + 1) / sectors

            def vertex(lat, lon):
                unit = (math.cos(lat) * math.cos(lon), math.sin(lat),
                        math.cos(lat) * math.sin(lon))
                normal = tuple(unit[i] / radii[i] for i in range(3))
                size = math.sqrt(sum(v * v for v in normal))
                return (tuple(center[i] + radii[i] * unit[i] for i in range(3)),
                        tuple(v / size for v in normal))

            faces = []
            if band < bands - 1:
                faces.append(((lat0, lon0), (lat1, lon0), (lat1, lon1)))
            if band > 0:
                faces.append(((lat0, lon0), (lat1, lon1), (lat0, lon1)))
            for points in faces:
                data = [vertex(*point) for point in points]
                triangle(material, [item[0] for item in data], data[0][1],
                         [item[1] for item in data])


# Fuselage: low-slung body, raised deck, purple forward canopy and camera.
ellipsoid("shell", (0, 0, 0), (0.48, 0.14, 0.21))
ellipsoid("deck", (-0.03, 0.105, 0), (0.33, 0.055, 0.16))
ellipsoid("nose", (0.38, 0.035, 0), (0.18, 0.09, 0.13))
cylinder("lens", (0.48, -0.065, 0), (0.54, -0.065, 0), 0.075)

for fore in (-1, 1):
    for side in (-1, 1):
        x, z = fore * 0.48, side * 0.53
        cylinder("arms", (fore * 0.18, 0, side * 0.10), (x, 0.02, z), 0.044)
        cylinder("motors", (x, -0.025, z), (x, 0.115, z), 0.095)
        cylinder("rotors", (x, 0.115, z), (x, 0.15, z), 0.025)
        # Two narrow blades give each rotor a visible 3D profile.
        box("rotors", (x, 0.155, z), (0.39, 0.018, 0.052))
        box("rotors", (x, 0.166, z), (0.052, 0.018, 0.39))

for side in (-1, 1):
    for fore in (-1, 1):
        cylinder("arms", (fore * 0.27, -0.08, side * 0.13),
                 (fore * 0.27, -0.24, side * 0.28), 0.022)
    cylinder("arms", (-0.37, -0.24, side * 0.28),
             (0.37, -0.24, side * 0.28), 0.022)


blob = bytearray()
views = []
accessors = []
primitives = []


def accessor(values, component_type, kind, count, extrema=False):
    while len(blob) % 4:
        blob.append(0)
    offset = len(blob)
    fmt = "f" if component_type == 5126 else "H"
    blob.extend(struct.pack("<" + fmt * len(values), *values))
    view = len(views)
    views.append({"buffer": 0, "byteOffset": offset, "byteLength": len(blob) - offset})
    entry = {"bufferView": view, "componentType": component_type,
             "count": count, "type": kind}
    if extrema:
        entry["min"] = [min(values[i::3]) for i in range(3)]
        entry["max"] = [max(values[i::3]) for i in range(3)]
    accessors.append(entry)
    return len(accessors) - 1


for material_index, part in enumerate(mesh.values()):
    count = len(part["positions"]) // 3
    position = accessor(part["positions"], 5126, "VEC3", count, True)
    normal = accessor(part["normals"], 5126, "VEC3", count)
    indices = accessor(part["indices"], 5123, "SCALAR", len(part["indices"]))
    primitives.append({"attributes": {"POSITION": position, "NORMAL": normal},
                       "indices": indices, "material": material_index})

document = {
    "asset": {"version": "2.0", "generator": "generate-drone-model.py"},
    "scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
    "meshes": [{"primitives": primitives}],
    "materials": [{"name": name, "pbrMetallicRoughness": {
        "baseColorFactor": color, "metallicFactor": 0.08, "roughnessFactor": 0.68},
        "doubleSided": True} for name, color in COLORS.items()],
    "buffers": [{"byteLength": len(blob)}],
    "bufferViews": views, "accessors": accessors,
}
json_chunk = json.dumps(document, separators=(",", ":")).encode("utf-8")
json_chunk += b" " * (-len(json_chunk) % 4)
blob.extend(b"\0" * (-len(blob) % 4))
length = 12 + 8 + len(json_chunk) + 8 + len(blob)
output = (struct.pack("<4sII", b"glTF", 2, length)
          + struct.pack("<I4s", len(json_chunk), b"JSON") + json_chunk
          + struct.pack("<I4s", len(blob), b"BIN\0") + blob)
path = Path(__file__).resolve().parent.parent / "src" / "assets" / "drone.glb"
path.write_bytes(output)
print(f"Wrote {path} ({len(output)} bytes)")
