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
            "ear": self.rounded_ear(),
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
    def rounded_ear():
        corners = [(-0.52, -0.45), (0.52, -0.45), (0.0, 0.65)]
        boundary = []
        for i, corner in enumerate(corners):
            before, after = corners[i - 1], corners[(i + 1) % 3]
            start = tuple(corner[j] * .86 + before[j] * .14 for j in range(2))
            end = tuple(corner[j] * .86 + after[j] * .14 for j in range(2))
            for step in range(7):
                t = step / 6
                boundary.append(tuple((1-t)**2*start[j] + 2*t*(1-t)*corner[j] + t*t*end[j] for j in range(2)))
        vertices, indices = [], []
        size = len(boundary)
        for factor, z, nz in ((.82, .20, .85), (1.0, .09, .30), (1.0, -.09, -.30), (.82, -.20, -.85)):
            for i, (x, y) in enumerate(boundary):
                previous, following = boundary[i-1], boundary[(i+1)%size]
                nx, ny = following[1]-previous[1], previous[0]-following[0]
                norm = math.hypot(nx, ny) or 1
                amount = math.sqrt(1-nz*nz)
                vertices.append((x*factor, y*factor, z, nx/norm*amount, ny/norm*amount, nz))
        for layer in range(3):
            for i in range(size):
                a, b = layer*size+i, layer*size+(i+1)%size
                indices.extend((a, b+size, b, a, a+size, b+size))
        for layer, z, sign in ((0, .20, 1), (3, -.20, -1)):
            center = len(vertices)
            vertices.append((0, 0, z, 0, 0, sign))
            for i in range(size):
                a, b = layer*size+i, layer*size+(i+1)%size
                indices.extend((center, a, b) if sign>0 else (center, b, a))
        return vertices, indices

    @staticmethod
    def tubes(paths, radius=.01, sides=8):
        vertices, indices = [], []
        for points in paths:
            offset = len(vertices)
            for i, p in enumerate(points):
                before, after = points[max(0, i-1)], points[min(len(points)-1, i+1)]
                dx, dy, dz = (after[j]-before[j] for j in range(3))
                length = math.sqrt(dx*dx+dy*dy+dz*dz) or 1
                tangent = (dx/length, dy/length, dz/length)
                planar = math.hypot(dx, dy) or 1
                normal = (-dy/planar, dx/planar, 0)
                binormal = (tangent[1]*normal[2]-tangent[2]*normal[1], tangent[2]*normal[0]-tangent[0]*normal[2], tangent[0]*normal[1]-tangent[1]*normal[0])
                for side in range(sides):
                    angle = math.tau*side/sides
                    n = tuple(normal[j]*math.cos(angle)+binormal[j]*math.sin(angle) for j in range(3))
                    vertices.append((*(p[j]+radius*n[j] for j in range(3)), *n))
            for ring in range(len(points)-1):
                for side in range(sides):
                    a = offset+ring*sides+side
                    b = offset+ring*sides+(side+1)%sides
                    indices.extend((a, b, a+sides, b, b+sides, a+sides))
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
        # Visual authority: LEFT white lucky cat in image.png. Surfaces are actual
        # meshes, not a picture on a plane. Never inflate dark duplicate shells:
        # they occlude white fur and turn the cat into a black-capped hybrid.
        for name, color, roughness, metallic in [
            ("porcelain", (1.0, .99, .965, 1), .78, 0),
            ("ink", (.055, .06, .075, 1), .90, 0),
            ("coral", (1.0, .27, .29, 1), .85, 0),
            ("red", (.89, .07, .095, 1), .68, 0),
            ("amber", (1.0, .53, .09, 1), .85, 0),
            ("green", (.11, .61, .15, 1), .85, 0),
            ("wave", (.91, 1.0, .87, 1), .90, 0),
            ("gold", (1.0, .68, .075, 1), .44, .30),
            ("goldrim", (1.0, .79, .24, 1), .42, .26),
            ("tongue", (1.0, .33, .36, 1), .80, 0),
            ("glasses", (.08, .26, .26, 1), .62, .10),
        ]:
            self.material(name, color, roughness, metallic)
        root = self.node("White lucky cat · selected left reference", location=(0, -1.20, 0), parent=None)
        self.nodes[root]["children"] = []

        def sphere(name, material, p, scale, parent=root, angle=0):
            return self.node(name, material=material, location=p, scale=scale,
                             rotation=(0, 0, math.sin(angle/2), math.cos(angle/2)), parent=parent)

        def strokes(name, material, paths, radius=.01, parent=root):
            self.geometry[name] = self.tubes(paths, radius)
            return self.node(name, material=material, geometry=name, parent=parent)

        def arc(cx, cy, rx, ry, z, start=0, end=math.pi, count=18):
            return [(cx+rx*math.cos(t), cy+ry*math.sin(t), z)
                    for t in (start+(end-start)*i/(count-1) for i in range(count))]

        # Low seated body and short paws; the much wider head leads the silhouette.
        sphere("Plump white body", "porcelain", (0, .63, .01), (.54, .62, .37))
        for sign in (-1, 1):
            sphere("White seated haunch", "porcelain", (sign*.38, .30, .06), (.23, .27, .30))
            sphere("Amber haunch spot", "amber", (sign*.47, .32, .27), (.12, .15, .045))
            sphere("Dark center of haunch spot", "ink", (sign*.49, .34, .303), (.057, .075, .018))
            sphere("Short white foot", "porcelain", (sign*.32, .10, .20), (.25, .11, .26))
        strokes("Foot toe creases", "ink", [[(x, .07, .447), (x, .115, .455)]
                    for x in (-.41, -.33, -.25, .25, .33, .41)], .006)
        # A small white tail stays behind the seated body.
        sphere("White tail curl", "porcelain", (.48, .45, -.18), (.17, .23, .17))
        sphere("Amber tail patch", "amber", (.58, .49, -.06), (.085, .105, .045))

        # Green triangular bib with real repeated wave geometry (one merged mesh).
        self.node("Green wave-pattern bib", material="green", geometry="ear",
                  location=(0, .99, .405), scale=(1.0, .45, .18),
                  rotation=(0, 0, 1, 0), parent=root)
        waves = []
        for row, y in enumerate((.84, .93, 1.02, 1.11)):
            for column in range(-4, 5):
                x = column*.14 + (.07 if row%2 else 0)
                for radius in (.068, .043, .020):
                    # Clip each scallop to the actual triangular front instead
                    # of omitting whole side motifs behind the central bell.
                    segment = []
                    for p in arc(x, y, radius, radius*.75, .456, count=20):
                        half_width = .405*(p[1]-.705)/.475
                        if .76 < p[1] < 1.145 and abs(p[0]) < half_width-.018:
                            segment.append(p)
                        else:
                            if len(segment)>1:
                                waves.append(segment)
                            segment = []
                    if len(segment)>1:
                        waves.append(segment)
        strokes("Dense ivory seigaiha bib waves", "wave", waves, .0065)

        # Red collar follows the neckline and remains readable beneath the head.
        collar = [(.49*math.cos(t), 1.18-.035*math.sin(t), .025+.43*math.sin(t))
                  for t in (math.tau*i/64 for i in range(65))]
        strokes("Continuous red collar", "red", [collar], .042)
        sphere("Gold bell loop", "gold", (0, 1.13, .48), (.06, .05, .035))
        sphere("Gold bell", "gold", (0, 1.035, .49), (.12, .115, .10))
        strokes("Bell rim and slot", "ink", [arc(0, 1.035, .108, .022, .583, math.pi, math.tau), [(0, 1.024, .591), (0, .974, .588)]], .007)
        sphere("Bell keyhole", "ink", (0, 1.016, .591), (.018, .022, .006))

        # Tall oval coin, held by the resting paw. Three conventional lucky-cat
        # coin characters are authored as raised strokes, with no font dependency.
        coin = self.node("Gold coin · held in resting paw", location=(.055, .43, .46),
                         rotation=(0, 0, math.sin(-.14/2), math.cos(-.14/2)), parent=root)
        sphere("Oval gold coin", "gold", (0, 0, 0), (.27, .43, .072), coin)
        self.node("Coin raised gold rim", material="goldrim", geometry="ring", location=(0, 0, .061),
                  scale=(.238, .389, .10), parent=coin)
        characters = [
            # 千
            [(-.075,.29,.080), (.07,.315,.080)],
            [(-.095,.245,.083), (.095,.245,.083)],
            [(0,.32,.083), (0,.155,.083)],
            # 万
            [(-.10,.105,.084), (.105,.105,.084)],
            [(.025,.102,.084), (-.01,.01,.084), (-.08,-.04,.084)],
            [(.00,.055,.084), (.078,.055,.084), (.075,-.035,.084), (.028,-.055,.084)],
            # 両
            [(-.105,-.12,.083), (.105,-.12,.083)],
            [(-.086,-.14,.083), (-.086,-.29,.079)],
            [(.086,-.14,.083), (.086,-.29,.079)],
            [(-.086,-.14,.083), (.086,-.14,.083)],
            [(-.04,-.12,.083), (-.04,-.255,.080)],
            [(.04,-.12,.083), (.04,-.255,.080)],
            [(-.086,-.23,.082), (.086,-.23,.082)],
        ]
        strokes("Coin lettering · 千万両", "ink", characters, .014, coin)

        # Left arm overlaps the upper coin, rather than becoming a separate lobe.
        strokes("Resting white arm", "porcelain", [[(-.43,.99,.19),(-.44,.90,.24),(-.38,.80,.34),(-.25,.72,.44)]], .135)
        sphere("Amber shoulder spot", "amber", (-.46,.93,.345), (.092,.14,.030), angle=-.28)
        sphere("Dark shoulder marking", "ink", (-.49,.94,.372), (.042,.069,.016), angle=-.28)
        sphere("Paw holding coin", "porcelain", (-.19,.705,.54), (.17,.092,.092), angle=-.35)
        strokes("Resting paw creases", "ink", [[(x,.694,.615),(x+.014,.742,.602)] for x in (-.27,-.21,-.15)], .0055)

        # Right paw curves up alongside the face; everything follows its pivot.
        arm = self.node("Raised lucky paw pivot", location=(.46,.91,.045), parent=root)
        points = []
        for i in range(22):
            t = i/21
            points.append((2*(1-t)*t*.34+t*t*.25, 2*(1-t)*t*.20+t*t*.73, .12*t*t))
        strokes("Curved raised white arm", "porcelain", [points], .13, arm)
        sphere("Rounded raised paw", "porcelain", (.25,.73,.13), (.175,.15,.14), arm)
        strokes("Raised paw toe creases", "ink", [[(x,.67,.251),(x-.008,.727,.266)] for x in (.18,.25,.32)], .006, arm)
        self.animate_rotation(arm, [(0,-.035),(.7,.060),(1.4,-.035),(2.1,.035),(2.8,-.035),(3.5,-.035)])

        head = self.node("Broad white head and ears", location=(0,1.60,.035), parent=root)
        sphere("Wide porcelain face", "porcelain", (0,0,0), (.72,.54,.435), head)
        for sign, label in ((-1,"Left"),(1,"Right")):
            angle = -sign*.18
            self.node(label+" pointed white ear", material="porcelain", geometry="ear",
                      location=(sign*.46,.47,-.012), scale=(.52,.51,.79),
                      rotation=(0,0,math.sin(angle/2),math.cos(angle/2)),parent=head)
            self.node(label+" pink inner ear", material="coral", geometry="ear",
                      location=(sign*.465,.487,.157), scale=(.30,.315,.08),
                      rotation=(0,0,math.sin(angle/2),math.cos(angle/2)),parent=head)

        def face_z(x,y,offset=.009):
            return .435*math.sqrt(max(.012,1-(x/.72)**2-(y/.54)**2))+offset

        def face_patch(name, material, cx, cy, rx, ry):
            # Paint follows the head's curvature, so cheeks and crown never float.
            vertices = [(cx,cy,face_z(cx,cy),0,0,1)]
            indices = []
            for i in range(49):
                angle = math.tau*i/48
                x,y = cx+rx*math.cos(angle),cy+ry*math.sin(angle)
                z = face_z(x,y)
                n=(x/.72**2,y/.54**2,z/.435**2)
                length=math.sqrt(sum(a*a for a in n))
                vertices.append((x,y,z,*(a/length for a in n)))
            for i in range(48):
                indices.extend((0,i+1,i+2))
            self.geometry[name]=(vertices,indices)
            self.node(name,material=material,geometry=name,parent=head)

        face_patch("Golden crown patch","amber",0,.405,.17,.09)
        face_patch("Dark crown center","ink",0,.438,.122,.045)
        for sign in (-1,1):
            face_patch("Coral cheek "+str(sign),"coral",sign*.49,-.18,.115,.115)
        gold_marks=[]
        for sign in (-1,1):
            for i in range(3):
                x=sign*(.18+i*.076)
                gold_marks.append([(x,.23,face_z(x,.23,.016)),(x+sign*.01,.31-i*.018,face_z(x+sign*.01,.31-i*.018,.016))])
        strokes("Small gold forehead strokes","amber",gold_marks,.0085,head)

        # Closed smiling eyes and painted whiskers, not oversized open pupils.
        expression=[]
        for sign in (-1,1):
            cx=sign*.235
            points=[]
            for i in range(19):
                t=i/18
                x=cx-.145+.29*t
                y=.065+.075*math.sin(math.pi*t)
                points.append((x,y,face_z(x,y,.022)))
            expression.append(points)
            for i in (-1,0,1):
                expression.append([(sign*.42,-.18+i*.045,face_z(sign*.42,-.18+i*.045,.022)),
                                   (sign*.59,-.18+i*.073,face_z(sign*.59,-.18+i*.073,.022))])
        expression += [[(0,-.105,.442),(0,-.16,.435)],
                       [(0,-.16,.435),(-.03,-.205,.427),(-.085,-.221,.415),(-.135,-.205,.410),(-.155,-.172,.414)],
                       [(0,-.16,.435),(.03,-.205,.427),(.085,-.221,.415),(.135,-.205,.410),(.155,-.172,.414)]]
        strokes("Closed smiles and whiskers","ink",expression,.0105,head)
        sphere("Small dark nose","ink",(0,-.093,.45),(.038,.025,.014),head)
        sphere("Happy open mouth","ink",(0,-.249,.400),(.065,.078,.025),head)
        sphere("Pink tongue","tongue",(0,-.271,.422),(.037,.050,.008),head)

        # Thin unobtrusive glasses retain Neko's established character feature.
        self.geometry["thin-glasses"] = self.torus(tube=.025)
        for sign in (-1,1):
            self.node("Small round glasses "+str(sign),material="glasses",geometry="thin-glasses",
                      location=(sign*.235,.087,.471),scale=(.162,.150,.08),parent=head)
        strokes("Glasses bridge and temples","glasses",[
                    [(-.073,.10,.472),(0,.115,.474),(.073,.10,.472)],
                    [(-.40,.10,.463),(-.58,.14,.29)],[(.40,.10,.463),(.58,.14,.29)]],.0045,head)
        self.animate_rotation(head,[(0,-.011),(.9,.012),(1.8,-.011),(2.7,.006),(3.5,-.011)])

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
