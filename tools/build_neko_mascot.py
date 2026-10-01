"""Build the Neko mascot as a small, animated, dependency-free glTF binary."""

from __future__ import annotations

import json
import math
import struct
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUTPUTS = [
    ROOT / "app/src/main/assets/models/neko.glb",
    ROOT / "preview/neko.glb",
]


class Model:
    def __init__(self) -> None:
        self.materials: list[dict] = []
        self.material_names: dict[str, int] = {}
        self.nodes: list[dict] = []
        self.meshes: list[dict] = []
        self.buffers = bytearray()
        self.buffer_views: list[dict] = []
        self.accessors: list[dict] = []
        self.animation_channels: list[dict] = []
        self.animation_samplers: list[dict] = []
        self.mesh_by_material: dict[str, int] = {}
        self.geometry = {
            "sphere": self.sphere(),
            "ring": self.torus(),
            "cylinder": self.cylinder(),
            "cone": self.cone(),
        }

    @staticmethod
    def sphere(segments: int = 36, rings: int = 24):
        vertices = []
        indices = []
        for row in range(rings + 1):
            phi = math.pi * row / rings
            for column in range(segments + 1):
                theta = math.tau * column / segments
                normal = (
                    math.sin(phi) * math.cos(theta),
                    math.cos(phi),
                    math.sin(phi) * math.sin(theta),
                )
                vertices.append((*normal, *normal))
        for row in range(rings):
            for column in range(segments):
                a = row * (segments + 1) + column
                b = a + segments + 1
                indices += [a, a + 1, b, a + 1, b + 1, b]
        return vertices, indices

    @staticmethod
    def torus(segments: int = 48, sides: int = 10, tube: float = 0.12):
        vertices = []
        indices = []
        for i in range(segments):
            theta = math.tau * i / segments
            for j in range(sides):
                phi = math.tau * j / sides
                radial = 1 + tube * math.cos(phi)
                normal = (math.cos(theta) * math.cos(phi), math.sin(theta) * math.cos(phi), math.sin(phi))
                vertices.append((radial * math.cos(theta), radial * math.sin(theta), tube * math.sin(phi), *normal))
        for i in range(segments):
            for j in range(sides):
                a = i * sides + j
                b = ((i + 1) % segments) * sides + j
                c = i * sides + (j + 1) % sides
                d = ((i + 1) % segments) * sides + (j + 1) % sides
                indices += [a, b, c, c, b, d]
        return vertices, indices

    @staticmethod
    def cylinder(segments: int = 20):
        vertices = []
        indices = []
        for y in (-0.5, 0.5):
            for i in range(segments):
                theta = math.tau * i / segments
                n = (math.cos(theta), 0.0, math.sin(theta))
                vertices.append((n[0], y, n[2], *n))
        for i in range(segments):
            a, b = i, (i + 1) % segments
            indices += [a, segments + a, b, b, segments + a, segments + b]
        # Flat caps, duplicated normals keep the cylinder crisp at each rim.
        for y, sign in ((-0.5, -1.0), (0.5, 1.0)):
            center = len(vertices)
            vertices.append((0.0, y, 0.0, 0.0, sign, 0.0))
            ring = len(vertices)
            for i in range(segments):
                theta = math.tau * i / segments
                vertices.append((math.cos(theta), y, math.sin(theta), 0.0, sign, 0.0))
            for i in range(segments):
                a, b = ring + i, ring + (i + 1) % segments
                indices += ([center, a, b] if sign < 0 else [center, b, a])
        return vertices, indices

    @staticmethod
    def cone(segments: int = 24):
        vertices = []
        indices = []
        for i in range(segments):
            theta = math.tau * i / segments
            n = (math.cos(theta), 0.35, math.sin(theta))
            length = math.sqrt(sum(v * v for v in n))
            vertices.append((math.cos(theta), -0.5, math.sin(theta), *(v / length for v in n)))
        tip = len(vertices)
        vertices.append((0.0, 0.5, 0.0, 0.0, 1.0, 0.0))
        for i in range(segments):
            indices += [i, tip, (i + 1) % segments]
        center = len(vertices)
        vertices.append((0.0, -0.5, 0.0, 0.0, -1.0, 0.0))
        for i in range(segments):
            indices += [center, i, (i + 1) % segments]
        return vertices, indices

    @staticmethod
    def quaternion_between_up(vector: tuple[float, float, float]) -> tuple[float, float, float, float]:
        x, y, z = vector
        length = math.sqrt(x * x + y * y + z * z)
        x, y, z = x / length, y / length, z / length
        dot = y
        if dot < -0.999999:
            return (1.0, 0.0, 0.0, 0.0)
        q = (-z, 0.0, x, 1.0 + dot)
        norm = math.sqrt(sum(v * v for v in q))
        return tuple(v / norm for v in q)

    def material(self, name: str, color: tuple[float, float, float, float], roughness=0.56, metallic=0.0):
        self.material_names[name] = len(self.materials)
        material = {
            "name": name,
            "pbrMetallicRoughness": {
                "baseColorFactor": list(color),
                "metallicFactor": metallic,
                "roughnessFactor": roughness,
            },
        }
        if color[3] < 1.0:
            material["alphaMode"] = "BLEND"
            material["doubleSided"] = True
        self.materials.append(material)

    def add_geometry(self, material: str, geometry: str):
        key = material + ":" + geometry
        if key in self.mesh_by_material:
            return self.mesh_by_material[key]
        vertices, indices = self.geometry[geometry]
        while len(self.buffers) % 4:
            self.buffers.append(0)
        first = len(self.buffers)
        for vertex in vertices:
            self.buffers.extend(struct.pack("<6f", *vertex))
        vertex_view = len(self.buffer_views)
        self.buffer_views.append({"buffer": 0, "byteOffset": first, "byteLength": len(self.buffers) - first, "byteStride": 24, "target": 34962})
        positions = [vertex[:3] for vertex in vertices]
        pos_accessor = len(self.accessors)
        self.accessors.append({"bufferView": vertex_view, "byteOffset": 0, "componentType": 5126, "count": len(vertices), "type": "VEC3", "min": [min(p[i] for p in positions) for i in range(3)], "max": [max(p[i] for p in positions) for i in range(3)]})
        normal_accessor = len(self.accessors)
        self.accessors.append({"bufferView": vertex_view, "byteOffset": 12, "componentType": 5126, "count": len(vertices), "type": "VEC3"})
        while len(self.buffers) % 4:
            self.buffers.append(0)
        index_offset = len(self.buffers)
        for index in indices:
            self.buffers.extend(struct.pack("<H", index))
        index_view = len(self.buffer_views)
        self.buffer_views.append({"buffer": 0, "byteOffset": index_offset, "byteLength": len(indices) * 2, "target": 34963})
        index_accessor = len(self.accessors)
        self.accessors.append({"bufferView": index_view, "componentType": 5123, "count": len(indices), "type": "SCALAR", "min": [min(indices)], "max": [max(indices)]})
        mesh_id = len(self.meshes)
        self.meshes.append({"name": key, "primitives": [{"attributes": {"POSITION": pos_accessor, "NORMAL": normal_accessor}, "indices": index_accessor, "material": self.material_names[material]}]})
        self.mesh_by_material[key] = mesh_id
        return mesh_id

    def node(self, name: str, *, material: str | None = None, geometry="sphere", location=(0.0, 0.0, 0.0), scale=(1.0, 1.0, 1.0), rotation=None, parent=0, children=None):
        value = {"name": name, "translation": list(location), "scale": list(scale)}
        if rotation is not None:
            value["rotation"] = list(rotation)
        if material is not None:
            value["mesh"] = self.add_geometry(material, geometry)
        if children:
            value["children"] = children
        node_id = len(self.nodes)
        self.nodes.append(value)
        if parent is not None:
            self.nodes[parent].setdefault("children", []).append(node_id)
        return node_id

    def animate_rotation(self, node_id: int, keys: list[tuple[float, float]]):
        while len(self.buffers) % 4:
            self.buffers.append(0)
        input_offset = len(self.buffers)
        for time, _ in keys:
            self.buffers.extend(struct.pack("<f", time))
        input_view = len(self.buffer_views)
        self.buffer_views.append({"buffer": 0, "byteOffset": input_offset, "byteLength": 4 * len(keys)})
        input_accessor = len(self.accessors)
        self.accessors.append({"bufferView": input_view, "componentType": 5126, "count": len(keys), "type": "SCALAR", "min": [keys[0][0]], "max": [keys[-1][0]]})
        output_offset = len(self.buffers)
        for _, angle in keys:
            self.buffers.extend(struct.pack("<4f", 0.0, 0.0, math.sin(angle / 2), math.cos(angle / 2)))
        output_view = len(self.buffer_views)
        self.buffer_views.append({"buffer": 0, "byteOffset": output_offset, "byteLength": 16 * len(keys)})
        output_accessor = len(self.accessors)
        self.accessors.append({"bufferView": output_view, "componentType": 5126, "count": len(keys), "type": "VEC4"})
        sampler = len(self.animation_samplers)
        self.animation_samplers.append({"input": input_accessor, "output": output_accessor, "interpolation": "LINEAR"})
        self.animation_channels.append({"sampler": sampler, "target": {"node": node_id, "path": "rotation"}})

    def make_mascot(self):
        swatches = {
            "outline": ((0.055, 0.11, 0.17, 1.0), 0.40),
            "cream": ((0.985, 0.967, 0.89, 1.0), 0.62),
            "goldfur": ((0.96, 0.56, 0.20, 1.0), 0.66),
            "stripe": ((0.75, 0.31, 0.12, 1.0), 0.74),
            "ear": ((0.94, 0.39, 0.40, 1.0), 0.72),
            "muzzle": ((1.0, 0.995, 0.95, 1.0), 0.62),
            "lens": ((0.27, 0.81, 0.84, 0.32), 0.20),
            "aqua": ((0.10, 0.75, 0.76, 1.0), 0.35),
            "gold": ((1.0, 0.68, 0.19, 1.0), 0.30),
            "bellshadow": ((0.50, 0.26, 0.08, 1.0), 0.48),
            "eye": ((0.045, 0.10, 0.15, 1.0), 0.35),
            "shine": ((1.0, 1.0, 0.99, 1.0), 0.22),
        }
        for name, (color, roughness) in swatches.items():
            self.material(name, color, roughness, 0.16 if name == "gold" else 0.0)

        root = self.node("Neko · head and body centered", location=(0.0, -1.08, 0.0), parent=None)
        self.nodes[0]["children"] = []

        def sphere(name, material, p, s, parent=root):
            return self.node(name, material=material, location=p, scale=s, parent=parent)

        def cylinder_between(name, material, start, end, radius, parent=root):
            direction = tuple(end[i] - start[i] for i in range(3))
            length = math.sqrt(sum(value * value for value in direction))
            center = tuple((start[i] + end[i]) / 2 for i in range(3))
            return self.node(name, material=material, geometry="cylinder", location=center, scale=(radius, length, radius), rotation=self.quaternion_between_up(direction), parent=parent)

        # Sitting body: an ink-colored soft outline under the warm white fur.
        sphere("Tail crescent · outer", "outline", (0.53, 0.48, -0.24), (0.28, 0.25, 0.22))
        for i, (p, s) in enumerate([
            ((0.63, 0.46, -0.24), (0.15, 0.15, 0.14)),
            ((0.76, 0.57, -0.23), (0.15, 0.16, 0.14)),
            ((0.73, 0.73, -0.20), (0.15, 0.17, 0.14)),
        ]):
            sphere(f"Curled striped tail {i + 1}", "goldfur", p, s)
        sphere("Tail tip", "cream", (0.62, 0.78, -0.18), (0.13, 0.12, 0.13))
        sphere("Body outline", "outline", (0.0, 0.64, -0.015), (0.60, 0.70, 0.43))
        sphere("Soft cream body", "cream", (0.0, 0.67, 0.035), (0.56, 0.66, 0.41))
        sphere("Paw left outline", "outline", (-0.34, 0.13, 0.15), (0.30, 0.17, 0.34))
        sphere("Paw left", "cream", (-0.34, 0.16, 0.17), (0.27, 0.14, 0.31))
        sphere("Paw right outline", "outline", (0.34, 0.13, 0.15), (0.30, 0.17, 0.34))
        sphere("Paw right", "cream", (0.34, 0.16, 0.17), (0.27, 0.14, 0.31))
        for paw_x, side in ((-0.41, "Left"), (-0.34, "Middle left"), (-0.27, "Inside left"), (0.27, "Inside right"), (0.34, "Middle right"), (0.41, "Right")):
            for x_offset in (0.0,):
                cylinder_between(f"{side} paw crease", "outline", (paw_x + x_offset, 0.09, 0.405), (paw_x + x_offset, 0.15, 0.421), 0.008)

        # Bib, bell, and the dark whisker pads echo the provided lucky-cat art.
        sphere("Teal bib", "aqua", (0.0, 0.68, 0.406), (0.33, 0.31, 0.060))
        sphere("Bib glint", "cream", (-0.12, 0.83, 0.452), (0.045, 0.11, 0.016))
        sphere("Bell loop", "gold", (0.0, 1.015, 0.435), (0.10, 0.045, 0.050))
        sphere("Bell", "gold", (0.0, 0.935, 0.481), (0.145, 0.14, 0.12))
        sphere("Bell lower rim", "bellshadow", (0.0, 0.90, 0.556), (0.12, 0.018, 0.015))
        cylinder_between("Bell slot", "bellshadow", (0.0, 0.905, 0.572), (0.0, 0.925, 0.574), 0.010)

        # Raised paw gets its own animated pivot so the model greets and talks.
        arm = self.node("Greet · waving paw", location=(0.40, 0.84, 0.12), parent=root)
        cylinder_between("Raised arm outline", "outline", (0.0, 0.0, 0.0), (0.18, 0.36, 0.06), 0.205, arm)
        cylinder_between("Raised arm", "cream", (0.0, 0.02, 0.012), (0.18, 0.37, 0.07), 0.17, arm)
        sphere("Paw outline · wave", "outline", (0.20, 0.45, 0.08), (0.25, 0.22, 0.21), arm)
        sphere("Paw · wave", "cream", (0.20, 0.47, 0.12), (0.215, 0.185, 0.18), arm)
        for x in (0.12, 0.20, 0.28):
            cylinder_between("Paw crease · wave", "outline", (x, 0.38, 0.287), (x, 0.43, 0.298), 0.008, arm)
        self.animate_rotation(arm, [(0.0, -0.06), (0.42, 0.27), (0.84, -0.05), (1.26, 0.23), (1.68, -0.06)])

        # Head, soft ink outline, plush tabby ears, and a bright white face.
        head = self.node("Head pivot", location=(0.0, 1.50, 0.0), parent=root)
        self.nodes[head]["children"] = []
        sphere("Head outline", "outline", (0.0, 0.03, -0.006), (0.63, 0.58, 0.43), head)
        sphere("Golden head", "goldfur", (0.0, 0.03, 0.027), (0.592, 0.545, 0.407), head)
        for sign, label in ((-1, "Left"), (1, "Right")):
            self.node(f"{label} ear outline", material="outline", geometry="cone", location=(sign * 0.395, 0.425, -0.015), scale=(0.285, 0.42, 0.225), rotation=(0.0, 0.0, -sign * 0.25, 0.969))
            self.node(f"{label} golden ear", material="goldfur", geometry="cone", location=(sign * 0.395, 0.435, 0.012), scale=(0.25, 0.37, 0.20), rotation=(0.0, 0.0, -sign * 0.25, 0.969))
            self.node(f"{label} rosy ear", material="ear", geometry="cone", location=(sign * 0.395, 0.445, 0.175), scale=(0.132, 0.265, 0.040), rotation=(0.0, 0.0, -sign * 0.25, 0.969))
        sphere("White face mask", "muzzle", (0.0, -0.075, 0.359), (0.52, 0.34, 0.125), head)
        sphere("Left cheek", "cream", (-0.19, -0.10, 0.431), (0.205, 0.165, 0.075), head)
        sphere("Right cheek", "cream", (0.19, -0.10, 0.431), (0.205, 0.165, 0.075), head)
        sphere("Left muzzle", "muzzle", (-0.11, -0.13, 0.486), (0.15, 0.13, 0.055), head)
        sphere("Right muzzle", "muzzle", (0.11, -0.13, 0.486), (0.15, 0.13, 0.055), head)
        sphere("Nose", "ear", (0.0, -0.062, 0.548), (0.055, 0.043, 0.028), head)
        cylinder_between("Mouth stem", "outline", (0.0, -0.09, 0.55), (0.0, -0.15, 0.55), 0.008, head)
        cylinder_between("Smile left", "outline", (0.0, -0.15, 0.55), (-0.07, -0.20, 0.54), 0.009, head)
        cylinder_between("Smile right", "outline", (0.0, -0.15, 0.55), (0.07, -0.20, 0.54), 0.009, head)

        # Little amber tabby marks above the round cyan-accented glasses.
        for index, (x, y, z, sx, sy, angle) in enumerate([
            (-0.24, 0.40, 0.275, 0.045, 0.145, -0.33),
            (-0.12, 0.47, 0.305, 0.035, 0.12, -0.13),
            (0.12, 0.47, 0.305, 0.035, 0.12, 0.13),
            (0.24, 0.40, 0.275, 0.045, 0.145, 0.33),
        ]):
            self.node(f"Tabby forehead mark {index + 1}", material="stripe", location=(x, y, z), scale=(sx, sy, 0.017), rotation=(0.0, 0.0, math.sin(angle / 2), math.cos(angle / 2)), parent=head)

        # Two lenses, glossy eyes, an ink-and-teal frame, and its bridge.
        for sign, label in ((-1, "Left"), (1, "Right")):
            x = sign * 0.235
            sphere(f"{label} eye white", "shine", (x, 0.085, 0.452), (0.108, 0.14, 0.044), head)
            sphere(f"{label} pupil", "eye", (x + sign * 0.018, 0.075, 0.492), (0.048, 0.080, 0.023), head)
            sphere(f"{label} eye catchlight", "shine", (x + sign * 0.009, 0.108, 0.514), (0.018, 0.029, 0.011), head)
            self.node(f"{label} teal glasses rim", material="aqua", geometry="ring", location=(x, 0.082, 0.510), scale=(0.225, 0.215, 0.027), parent=head)
            self.node(f"{label} glasses outline", material="outline", geometry="ring", location=(x, 0.082, 0.524), scale=(0.207, 0.197, 0.035), parent=head)
            sphere(f"{label} transparent lens", "lens", (x, 0.082, 0.522), (0.171, 0.163, 0.019), head)
            cylinder_between(f"{label} glasses arm", "outline", (sign * 0.42, 0.096, 0.46), (sign * 0.55, 0.132, 0.36), 0.018, head)
        cylinder_between("Glasses bridge", "outline", (-0.057, 0.09, 0.526), (0.057, 0.09, 0.526), 0.024, head)

        # Three whiskers per side; laid against the muzzle to read at phone size.
        for sign, label in ((-1, "Left"), (1, "Right")):
            for whisker in range(3):
                y = -0.12 + (whisker - 1) * 0.075
                cylinder_between(f"{label} whisker {whisker + 1}", "outline", (sign * 0.28, y, 0.497), (sign * (0.47 + 0.02 * abs(whisker - 1)), y + (whisker - 1) * 0.055, 0.46), 0.0065, head)

        self.animate_rotation(head, [(0.0, -0.018), (0.55, 0.018), (1.1, -0.018), (1.65, 0.008), (2.2, -0.018)])

        # Tiny supporting toes on the cream feet and a warm floor shadow.
        for sign in (-1, 1):
            for i in (-1, 0, 1):
                sphere("Paw toe", "cream", (sign * 0.34 + i * 0.09, 0.165, 0.398), (0.045, 0.055, 0.035))

    def to_glb(self) -> bytes:
        document = {
            "asset": {"version": "2.0", "generator": "Neko mascot builder"},
            "scene": 0,
            "scenes": [{"nodes": [0]}],
            "nodes": self.nodes,
            "meshes": self.meshes,
            "materials": self.materials,
            "accessors": self.accessors,
            "bufferViews": self.buffer_views,
            "buffers": [{"byteLength": len(self.buffers)}],
            "animations": [{"name": "A warm hello", "samplers": self.animation_samplers, "channels": self.animation_channels}],
        }
        json_bytes = json.dumps(document, separators=(",", ":")).encode("utf-8")
        json_bytes += b" " * ((-len(json_bytes)) % 4)
        binary = bytes(self.buffers) + b"\x00" * ((-len(self.buffers)) % 4)
        total_size = 12 + 8 + len(json_bytes) + 8 + len(binary)
        return b"glTF" + struct.pack("<II", 2, total_size) + struct.pack("<I4s", len(json_bytes), b"JSON") + json_bytes + struct.pack("<I4s", len(binary), b"BIN\x00") + binary


def build():
    model = Model()
    model.make_mascot()
    glb = model.to_glb()
    for path in OUTPUTS:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(glb)
    print(f"Wrote a {len(model.nodes)}-node, {len(model.meshes)}-mesh animated cat to app and preview ({len(glb):,} bytes).")


if __name__ == "__main__":
    build()
