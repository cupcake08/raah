"""Raah handlebar navigator: enclosure, key and bar mount, built in Fusion.

One script builds every part in the ACTIVE design, which must carry the root
attribute raah/design = enclosure-r1 (build() refuses to touch anything else).

Frame (mm): origin at the centre of the display's active area, on the front of
the panel glass. +Y up the face, +X to the rider's right, +Z out of the face
toward the rider. The device is a portrait slab: a XIAO ESP32C3 sits at the
chin with its USB-C in the bottom wall, and a flat 523450 cell lies on the lid
behind the display.

Run from a Fusion script:
    runpy.run_path(<this file>)['build'](['refs', 'tub', ...])
Stages: refs, tub, front_parts, lid, tab, mount, look, check, export, render
(and switches, which rebuilds just the key switch reference).
(This design is in direct-modelling mode: place occurrences when you create
them; a later transform2 is undone by the next feature.)
"""
import math
import os
import traceback

import adsk.core
import adsk.fusion

MM = 0.1  # Fusion API lengths are in cm
HERE = os.path.dirname(os.path.abspath(__file__))
CASE_DIR = os.path.dirname(HERE)
EXPORT_DIR = os.path.join(CASE_DIR, 'exports')

NEW = adsk.fusion.FeatureOperations.NewBodyFeatureOperation
JOIN = adsk.fusion.FeatureOperations.JoinFeatureOperation
CUT = adsk.fusion.FeatureOperations.CutFeatureOperation
INTERSECT = adsk.fusion.FeatureOperations.IntersectFeatureOperation

# ---------------------------------------------------------------- parameters

# Display module: 2.8" ILI9341 SPI, red PCB (LCDwiki MSP2807 drawing V1.0).
# Drawn header-down; FLIP_DISPLAY turns module and gasket 180 deg about the
# active-area centre, so the window never moves (TN bad side, Mirror X/Y).
FLIP_DISPLAY = False
LCD_HW = 25.0                              # PCB and glass half width
PCB_Y0, PCB_Y1 = -47.9, 38.1               # 86.0 PCB, AA centre 38.1 below its top
GLASS_Y0, GLASS_Y1 = -37.5, 31.7           # 69.2 glass, 6.40 below the PCB top
Z_PCB_F, Z_PCB_B = -4.0, -5.6              # 5.6 total thickness, glass front at 0
Z_BACKPARTS = -7.8                         # 2.2 max SMD on the back
AA_HX, AA_HY = 21.6, 28.8                  # 43.2 x 57.6 active area
VA_HX, VA_Y0, VA_Y1 = 22.6, -29.8, 29.65   # 45.2 x 59.45 viewing area
LCD_HOLES = [(-22.0, 35.1), (22.0, 35.1), (-22.0, -40.98), (22.0, -40.98)]
HDR_Y, HDR_HX = -45.9, 16.51               # 1x14 header, 2.0 from the PCB edge
Z_HDR_BODY, Z_PIN_TIP, Z_STUB = -8.1, -9.6, -2.5   # pins trimmed 1.5 past the header body

# Shell (front tub + skirt; the lid sits inside the skirt)
X_OUT, Y_TOP, Y_BOT, R_OUT = 29.8, 50.8, -64.0, 6.4
X_IN, Y_IN_TOP, Y_IN_BOT, R_IN = 27.4, 48.4, -61.6, 4.0
Z_FACE_IN = 0.6                            # back of the face skin
# Lens: 2.0 cast acrylic, flush in a pocket, bonded on a die-cut 3M VHB 4905
# ring (0.5). It covers the window and the sensor dot: one sealed face.
LENS_T, VHB_T, LENS_FLOOR, VHB_W = 2.0, 0.5, 1.2, 2.0
LENS_HX, LENS_Y0, LENS_Y1, LENS_R, LENS_CLR = 25.4, -46.0, 33.2, 3.0, 0.15
Z_LENS_SEAT = Z_FACE_IN + LENS_FLOOR       # 1.8, pocket floor
Z_FACE = Z_LENS_SEAT + VHB_T + LENS_T      # 4.3, lens flush with the face
Z_CHIN_IN = -1.6                           # chin skin is thicker behind the key
Z_RIM = -14.8                              # gasket seat (wall ledge + column ends), 1.2 under the USB tunnel
Z_BACK = -17.5                             # skirt end = lid outer face
SKIRT = 1.2
GASKET_T, GASKET_SET = 0.8, 0.7            # lid gasket (TPU, 4 x 0.2 layers), printed and set
Z_LID_IN = Z_RIM - GASKET_SET              # -15.5
FACE_EDGE_R, BACK_EDGE_R = 2.0, 0.8

# Window through the pocket floor: active area + 0.6 a side, bevelled toward the lens
AP_HX, AP_HY, AP_R, AP_BEVEL = 22.2, 29.4, 0.8, 0.8

# Display gasket (TPU): locates the module and cushions the glass (the lens
# does the sealing), flips with it
GK_HX, GK_HY = 25.0, 38.6                  # outer, symmetric about the AA centre
GK_WIN_HX, GK_WIN_HY = 22.7, 29.9
GK_T = 0.6                                 # 3 x 0.2 layers, just touches the glass
Z_GK_LIP = -3.4

# Module retention: snap arms over the PCB edges, ribs for X, ribs for the gasket
ARM_Y, ARM_W, ARM_X0, ARM_T, ARM_HOOK = 36.0, 6.0, 25.4, 1.0, 0.6
Z_ARM_TIP = -6.3
RIB_X = 25.15
SIDE_RIB_Y = (-44.5, -20.0, 0.0, 20.0, 44.5)

# Chin key (yellow), its stem, guide, TPU membrane and switch carrier.
# Two 6x6x5 tact switches sit near the key's ends, wired in parallel, so a press
# anywhere on the 46 mm key closes one. (With one off-centre switch the key
# could rock in its guide without clicking.) The switches are the hard stop;
# the cap has more room than that, even when an end press tilts the key.
# Only a nub over each switch presses the membrane, so the whole span between
# the clamps flexes (stiff TPU cannot shear through a thin band like silicone).
KEY_HX, KEY_Y0, KEY_Y1, KEY_R = 23.0, -60.0, -47.8, 4.0
KEY_T, KEY_LIFT, POCKET_D, KEY_CLR = 3.4, 1.3, 2.0, 0.3   # lift: cap to pocket floor at rest
KEY_YC = (KEY_Y0 + KEY_Y1)/2               # -53.9, the cap
STEM_YC = -55.0                            # stem, guide and carrier sit low, at the chin
STEM_HX, STEM_HY, STEM_CLR, BUMP = 19.3, 4.0, 0.15, 0.3
GUIDE_WALL, GUIDE_END_WALL, Z_GUIDE_END = 1.5, 4.5, -5.0
MEMB_FREE = 2.5                            # the membrane flexes this far past the stem's ends
GX = STEM_HX + STEM_CLR + GUIDE_END_WALL   # guide half sizes
GY = STEM_HY + STEM_CLR + GUIDE_WALL
MEMB_HX = STEM_HX + STEM_CLR + MEMB_FREE + 0.9   # 0.9 clamp band at the ends
Z_STEM_TIP = Z_GUIDE_END + 1.0             # stem walls: still clear of the membrane at the stop
MEMB_T = 0.6                               # TPU membrane, 3 x 0.2 layers
NUB_R, NUB_GAP = 1.4, 0.1                  # 0.1 + 0.4 to the switch + 0.25 travel = 0.75 to the stop
EAR_X, EAR_R, Z_EAR_END = 25.2, 2.2, -5.5  # carrier lands here: membrane clamped 0.1
Z_CARRIER_B = -7.1
SW_XS, SW_Y, SW_BODY, SW_ACT_R = (-17.9, 17.9), -55.6, 6.0, 1.75   # 6x6x5 tact switches
SW_LEG_X, SW_LEG_Y = 3.25, 2.25            # leg half pitches: 6.5 across the body, 4.5 along a side
# Only the two chin-side (-y) legs of each switch are used; the floor has no
# holes for the other two, which are snipped off. The cradle is square, so a switch can go in either way round and the two
# chin-side legs can always be an open pair: there are holes for both turns.
# As modelled, the legs run along Y.
Z_SW_BODY0, Z_SW_BODY1, Z_SW_ACT = -11.0, -7.5, -6.0
CRADLE_WALL, Z_CRADLE_F = 0.8, -11.6       # printed cradles; legs bent flat under the floor
SW_SOLDER = 1.5                            # bent leg, solder and wire end, under the floor
Z_RECESS = -6.4                            # carrier relief under the membrane

# Light sensor dot and wordmark
DOT_Y, DOT_R = -42.0, 1.5
LDR_R, Z_LDR0, Z_LDR1 = 2.55, -1.6, 0.5    # GL5528, press-fit, looking through the lens
CUP_R = 3.2
WORD = 'RAAH'
WORD_X0, WORD_BASE, WORD_CAP, WORD_PITCH, WORD_DEPTH = -22.2, -43.5, 3.5, 3.55, 0.4

# Lid screws: M2 heat-set inserts in four corner columns and two on the ends
# (the lower corners sit up the sides, clear of the switch cradles and the carrier screws)
COLS = [(-24.0, 44.8), (24.0, 44.8), (-25.6, -57.6), (25.6, -57.6), (10.0, 45.6), (10.0, -59.2)]
BEAD_W, BEAD_H = 0.6, 0.3                  # seal bead on the rim, bites the lid gasket
VENT = (-22.8, 10.0)                       # ePTFE vent sticker inside the lid, beside the cell
COL_R, INSERT_R, INSERT_D = 2.8, 1.6, 4.5
Z_COL_TOP = Z_RIM + 5.5                    # under the carrier screw heads

# Seeed XIAO ESP32C3 (21 x 17.8, PCB 1.2): top side toward the face, USB-C end
# down. It charges the cell (pads underneath) and makes the 3.3 V, so there is
# no charger or regulator board. Its receptacle face is 0.3 inside the bottom wall.
XI_X0, XI_X1, XI_Y0, XI_Y1 = -12.4, 5.4, -60.3, -39.3
Z_XI_B, Z_XI_T = -13.0, -11.8              # parts on top clear the trimmed display header pins
XI_XC = (XI_X0 + XI_X1)/2                  # -3.5: clear of the bottom-middle column
USB_HW, USB_L, USB_H, USB_OVER = 4.47, 7.35, 3.26, 1.0   # receptacle; overhangs the PCB edge
USB_X, USB_Z = XI_XC, Z_XI_T + USB_H/2
# BAT pads on the underside, mid-board on the D0-D6 edge (Seeed footprint, pads 21/22);
# the U.FL socket is on top beside the USB-C, at the 5V end
XI_BAT = [(XI_X1 - 4.445, XI_Y0 + 8.213), (XI_X1 - 4.445, XI_Y0 + 10.118)]
# Tunnel through the bottom wall, as big as a plug's overmold (12.35 x 6.5 max):
# the port is 2.7 behind the face, so the overmold has to go in for a full mate.
TUN_HW, TUN_HH, TUN_R = 6.5, 3.4, 3.0

# Antenna: the XIAO's FPC (40 x 20, 80 coax), stuck inside the lid between the
# board and the cell, clear of the cell's foil; its coax runs up the left side.
ANT_X0, ANT_X1, ANT_Y0, ANT_Y1 = -26.8, 13.2, -37.6, -17.6
# 523450 LiPo (5.2 x 34 x 50, 1500 mAh), its protection board folded over the
# end toward the antenna; on a 0.5 foam pad between low walls on the lid. Its
# leads run down the right side and under the XIAO to the pads. The pocket
# takes cells up to about 7 thick.
BAT_HX, BAT_Y0, BAT_Y1, BAT_PCM = 17.0, -14.0, 39.0, 3.0
Z_BAT_B, Z_BAT_F = Z_LID_IN + 0.5, Z_LID_IN + 5.7
LEAD_X0, LEAD_X1 = ANT_X1 + 0.3, ANT_X1 + 1.9      # the two cell leads, twisted

# Quarter-turn tab on the lid (Garmin-pattern, check the coupon first)
TAB_C = (0.0, -4.0)
TAB_BASE_R, TAB_BASE_T = 16.0, 1.6
TAB_NECK_R, TAB_NECK_H = 10.5, 2.4
TAB_EAR_R, TAB_EAR_T, TAB_EAR_DEG = 14.0, 1.8, 70.0
TAB_SCREWS = [(-2.5, 2.0), (2.5, -10.0)]   # 6.5 off the tab centre
TAB_GASKET = 0.6


# ---------------------------------------------------------------- helpers

def P(x, y, z=0.0):
    return adsk.core.Point3D.create(x*MM, y*MM, z*MM)


def V(d):
    return adsk.core.ValueInput.createByReal(d*MM)


def offset_plane(comp, base, target, axis):
    """Hidden construction plane parallel to base whose axis coordinate is target."""
    for sign in (1, -1):
        pi = comp.constructionPlanes.createInput()
        pi.setByOffset(base, adsk.core.ValueInput.createByReal(sign*target*MM))
        pl = comp.constructionPlanes.add(pi)
        o = pl.geometry.origin
        if abs({'x': o.x, 'y': o.y, 'z': o.z}[axis]/MM - target) < 1e-4:
            pl.isLightBulbOn = False
            return pl
        pl.deleteMe()
    raise RuntimeError('cannot place plane at %s=%s' % (axis, target))


def sketch_xy(comp, z, name):
    base = comp.xYConstructionPlane
    sk = comp.sketches.add(base if abs(z) < 1e-9 else offset_plane(comp, base, z, 'z'))
    sk.name = name
    return sk


def sketch_on(comp, base, target, axis, name):
    sk = comp.sketches.add(base if abs(target) < 1e-9 else offset_plane(comp, base, target, axis))
    sk.name = name
    return sk


def rrect(sk, x0, y0, x1, y1, r=0.0):
    """Rectangle on an XY sketch, optionally with corner radius r."""
    L = sk.sketchCurves.sketchLines
    if r <= 0:
        L.addTwoPointRectangle(P(x0, y0), P(x1, y1))
        return
    A = sk.sketchCurves.sketchArcs
    br = A.addByCenterStartSweep(P(x1-r, y0+r), P(x1-r, y0), math.pi/2)
    tr = A.addByCenterStartSweep(P(x1-r, y1-r), P(x1, y1-r), math.pi/2)
    tl = A.addByCenterStartSweep(P(x0+r, y1-r), P(x0+r, y1), math.pi/2)
    bl = A.addByCenterStartSweep(P(x0+r, y0+r), P(x0, y0+r), math.pi/2)
    L.addByTwoPoints(br.endSketchPoint, tr.startSketchPoint)
    L.addByTwoPoints(tr.endSketchPoint, tl.startSketchPoint)
    L.addByTwoPoints(tl.endSketchPoint, bl.startSketchPoint)
    L.addByTwoPoints(bl.endSketchPoint, br.startSketchPoint)


def poly_xy(sk, pts):
    L = sk.sketchCurves.sketchLines
    first = L.addByTwoPoints(P(*pts[0]), P(*pts[1]))
    prev = first
    for p in pts[2:]:
        prev = L.addByTwoPoints(prev.endSketchPoint, P(*p))
    L.addByTwoPoints(prev.endSketchPoint, first.startSketchPoint)


def poly_world(sk, pts):
    """Closed polygon from world-space points (any sketch plane)."""
    L = sk.sketchCurves.sketchLines
    sp = [sk.modelToSketchSpace(p) for p in pts]
    first = L.addByTwoPoints(sp[0], sp[1])
    prev = first
    for p in sp[2:]:
        prev = L.addByTwoPoints(prev.endSketchPoint, p)
    L.addByTwoPoints(prev.endSketchPoint, first.startSketchPoint)


def _nearest(arc, p):
    s, e = arc.startSketchPoint, arc.endSketchPoint
    return s if s.geometry.distanceTo(p) <= e.geometry.distanceTo(p) else e


def rrect_mapped(sk, f, u0, v0, u1, v1, r):
    """Rounded rectangle in (u, v); f(u, v) maps to a world Point3D."""
    m = sk.modelToSketchSpace
    A = sk.sketchCurves.sketchArcs
    L = sk.sketchCurves.sketchLines
    k = r*(1 - math.sqrt(0.5))
    corners = [((u1-r, v0), (u1-k, v0+k), (u1, v0+r)),
               ((u1, v1-r), (u1-k, v1-k), (u1-r, v1)),
               ((u0+r, v1), (u0+k, v1-k), (u0, v1-r)),
               ((u0, v0+r), (u0+k, v0+k), (u0+r, v0))]
    arcs = []
    for a, b, c in corners:
        arcs.append((A.addByThreePoints(m(f(*a)), m(f(*b)), m(f(*c))), m(f(*a)), m(f(*c))))
    for i in range(4):
        arc, _, end_p = arcs[i]
        nxt, start_p, _ = arcs[(i+1) % 4]
        L.addByTwoPoints(_nearest(arc, end_p), _nearest(nxt, start_p))


def circle_xy(sk, x, y, r):
    sk.sketchCurves.sketchCircles.addByCenterRadius(P(x, y), r*MM)


def circle_world(sk, c, r):
    sk.sketchCurves.sketchCircles.addByCenterRadius(sk.modelToSketchSpace(c), r*MM)


def all_profiles(sk):
    oc = adsk.core.ObjectCollection.create()
    for p in sk.profiles:
        oc.add(p)
    return oc


def profiles_where(sk, test):
    """Profiles whose centroid (sketch space, mm) passes test(x, y)."""
    oc = adsk.core.ObjectCollection.create()
    for p in sk.profiles:
        c = p.areaProperties().centroid
        if test(c.x/MM, c.y/MM):
            oc.add(p)
    return oc


def largest_profile(sk):
    return max(sk.profiles, key=lambda p: p.areaProperties().area)


def extrude(comp, profs, dist, op, up=True, bodies=None):
    ef = comp.features.extrudeFeatures
    inp = ef.createInput(profs, op)
    d = (adsk.fusion.ExtentDirections.PositiveExtentDirection if up
         else adsk.fusion.ExtentDirections.NegativeExtentDirection)
    inp.setOneSideExtent(adsk.fusion.DistanceExtentDefinition.create(V(dist)), d)
    if bodies is not None:
        inp.participantBodies = bodies
    return ef.add(inp)


def extrude_sym(comp, profs, total, op, bodies=None):
    ef = comp.features.extrudeFeatures
    inp = ef.createInput(profs, op)
    inp.setSymmetricExtent(V(total), True)
    if bodies is not None:
        inp.participantBodies = bodies
    return ef.add(inp)


def zbox(comp, x0, x1, y0, y1, z0, z1, op=NEW, r=0.0, bodies=None, name=None):
    """Box (optionally round-cornered in plan) from z0 up to z1."""
    sk = sketch_xy(comp, z0, name or 'box')
    rrect(sk, x0, y0, x1, y1, r)
    f = extrude(comp, sk.profiles.item(0), z1 - z0, op, bodies=bodies)
    if op == NEW:
        b = f.bodies.item(0)
        if name:
            b.name = name
        return b
    return f


def zcyl(comp, x, y, r, z0, z1, op=NEW, bodies=None, name=None):
    sk = sketch_xy(comp, z0, name or 'cylinder')
    circle_xy(sk, x, y, r)
    f = extrude(comp, sk.profiles.item(0), z1 - z0, op, bodies=bodies)
    if op == NEW:
        b = f.bodies.item(0)
        if name:
            b.name = name
        return b
    return f


def combine(comp, target, tools, op=JOIN, keep=False):
    oc = adsk.core.ObjectCollection.create()
    for t in tools:
        oc.add(t)
    ci = comp.features.combineFeatures.createInput(target, oc)
    ci.operation = op
    ci.isKeepToolBodies = keep
    return comp.features.combineFeatures.add(ci)


def fillet(comp, edges, r):
    oc = adsk.core.ObjectCollection.create()
    for e in edges:
        oc.add(e)
    if oc.count == 0:
        return None
    fi = comp.features.filletFeatures.createInput()
    fi.edgeSetInputs.addConstantRadiusEdgeSet(oc, V(r), True)
    return comp.features.filletFeatures.add(fi)


def chamfer(comp, edges, d):
    oc = adsk.core.ObjectCollection.create()
    for e in edges:
        oc.add(e)
    if oc.count == 0:
        return None
    ci = comp.features.chamferFeatures.createInput2()
    ci.chamferEdgeSets.addEqualDistanceChamferEdgeSet(oc, V(d), True)
    return comp.features.chamferFeatures.add(ci)


def planar_face(body, axis, value, positive):
    best = None
    for f in body.faces:
        if not isinstance(f.geometry, adsk.core.Plane):
            continue
        n = f.evaluator.getNormalAtPoint(f.pointOnFace)[1]
        comp = {'x': n.x, 'y': n.y, 'z': n.z}[axis]
        pos = {'x': f.pointOnFace.x, 'y': f.pointOnFace.y, 'z': f.pointOnFace.z}[axis]/MM
        if abs(abs(comp) - 1) < 1e-6 and (comp > 0) == positive and abs(pos - value) < 1e-3:
            if best is None or f.area > best.area:
                best = f
    return best


def loop_edges(face, outer=True):
    return [e for lp in face.loops if lp.isOuter == outer for e in lp.edges]


def edges_at_z(body, z, tol=1e-3):
    """Edges lying entirely in the plane Z = z."""
    out = []
    for e in body.edges:
        bb = e.boundingBox
        if abs(bb.minPoint.z/MM - z) < tol and abs(bb.maxPoint.z/MM - z) < tol:
            out.append(e)
    return out


def bbox_str(b):
    bb = b.boundingBox
    return 'x %.2f..%.2f y %.2f..%.2f z %.2f..%.2f' % (
        bb.minPoint.x/MM, bb.maxPoint.x/MM, bb.minPoint.y/MM, bb.maxPoint.y/MM,
        bb.minPoint.z/MM, bb.maxPoint.z/MM)


def new_part(root, name, m=None):
    """Fresh, empty component at the root (replaces one of the same name).
    Placement is set here: in this direct design a later transform2 does not
    stick (the next feature puts the occurrence back)."""
    for i in range(root.occurrences.count - 1, -1, -1):
        o = root.occurrences.item(i)
        if o.component.name == name:
            o.deleteMe()
    occ = root.occurrences.addNewComponent(m or adsk.core.Matrix3D.create())
    occ.component.name = name
    return occ.component, occ


def find_occ(root, name):
    for i in range(root.occurrences.count):
        o = root.occurrences.item(i)
        if o.component.name == name:
            return o
    return None


def col_webs(cx, cy, reach):
    """Boxes tying a screw column to the wall(s): corners get two, ends one."""
    sy = 1 if cy > 0 else -1
    wy = (Y_IN_TOP + reach) if sy > 0 else (Y_IN_BOT - reach)
    if abs(cx) < 20.0:
        return [(cx - COL_R, cx + COL_R, min(cy, wy), max(cy, wy))]
    sx = 1 if cx > 0 else -1
    wx = sx*(X_IN + reach)
    return [(min(cx - sx*COL_R, wx), max(cx - sx*COL_R, wx), min(cy, wy), max(cy, wy)),
            (min(cx, wx), max(cx, wx), min(cy - sy*COL_R, wy), max(cy - sy*COL_R, wy))]


def col_top(cx, cy):
    """Column end; the one under the display header (flipped module) stops short of its pins."""
    if abs(cx) < HDR_HX + 1.27 + COL_R and abs(abs(cy) - abs(HDR_Y)) < 1.27 + COL_R:
        return Z_PIN_TIP - 0.6
    return Z_COL_TOP


def outline(sk, inset=0.0):
    """Outer plan outline of the shell, shrunk by inset."""
    r = max(R_OUT - inset, 0.2)
    rrect(sk, -X_OUT + inset, Y_BOT + inset, X_OUT - inset, Y_TOP - inset, r)


def cavity(sk, grow=0.0):
    rrect(sk, -X_IN - grow, Y_IN_BOT - grow, X_IN + grow, Y_IN_TOP + grow, R_IN + grow)


def heal_text_cuts(comp, body):
    """Recreate text cuts that finished with a warning (Fusion sometimes fails a
    glyph with a small counter on the first pass; the same cut redone computes)."""
    redone = []
    for f in list(comp.features.extrudeFeatures):
        t = f.profile
        if not isinstance(t, adsk.fusion.SketchText):
            continue
        if f.healthState == adsk.fusion.FeatureHealthStates.HealthyFeatureHealthState:
            continue
        d = f.extentOne.distance.value/MM
        f.deleteMe()
        extrude(comp, t, d, CUT, up=False, bodies=[body])
        redone.append(t.text)
    return redone


# ---------------------------------------------------------------- reference models

# R1's test board (Edgehax S3 Pro), its 102050 cell and the power boards
OLD_REFS = ('REF - Edgehax S3 Pro', 'REF - 102050 LiPo 1000mAh', 'REF - TP4056 charger',
            'REF - Perfboard (LDO, divider)')


def build_refs(app, design, root):
    """XIAO ESP32C3, display module, cell, antenna, switches, LDR."""
    for name in OLD_REFS:
        o = find_occ(root, name)
        if o is not None:
            o.deleteMe()
    comp, _ = new_part(root, 'REF - XIAO ESP32C3')
    zbox(comp, XI_X0, XI_X1, XI_Y0, XI_Y1, Z_XI_B, Z_XI_T, r=0.8, name='XIAO PCB')
    zbox(comp, USB_X - USB_HW, USB_X + USB_HW, XI_Y0 - USB_OVER, XI_Y0 - USB_OVER + USB_L,
         Z_XI_T, Z_XI_T + USB_H, name='XIAO USB-C')
    zbox(comp, XI_X0 + 1.0, XI_X1 - 1.0, XI_Y0 - USB_OVER + USB_L + 0.3, XI_Y1 - 1.0, Z_XI_T, Z_XI_T + 1.2,
         name='XIAO parts')
    zbox(comp, XI_X0 + 0.6, XI_X0 + 3.6, XI_Y0 + 0.6, XI_Y0 + 3.6, Z_XI_T, Z_XI_T + 1.25, name='XIAO U.FL')
    zcyl(comp, XI_X0 + 2.1, XI_Y0 + 2.1, 1.0, Z_XI_T + 1.25, Z_XI_T + 2.1, name='antenna plug')
    for x, y in XI_BAT:
        zbox(comp, x - 1.25, x + 1.25, y - 0.55, y + 0.55, Z_XI_B - 0.05, Z_XI_B, name='XIAO BAT pad')

    comp, _ = new_part(root, 'REF - ILI9341 2.8in module', flip_matrix())
    build_display_ref(comp)

    comp, _ = new_part(root, 'REF - 523450 LiPo 1500mAh')
    zbox(comp, -BAT_HX, BAT_HX, BAT_Y0 + BAT_PCM, BAT_Y1, Z_BAT_B, Z_BAT_F, r=1.0, name='cell 523450')
    zbox(comp, -BAT_HX + 3.0, BAT_HX - 3.0, BAT_Y0, BAT_Y0 + BAT_PCM, Z_BAT_B + 0.8, Z_BAT_F - 0.8,
         name='cell protection board')
    yb = min(y for _, y in XI_BAT) - 0.3                 # leads: down the right side, under the XIAO
    zl0, zl1 = Z_LID_IN + 0.1, Z_LID_IN + 1.5
    zbox(comp, LEAD_X0, BAT_HX - 3.0, BAT_Y0 - 1.0, BAT_Y0, zl0, Z_BAT_B + 2.0, name='cell leads')
    zbox(comp, LEAD_X0, LEAD_X1, yb, BAT_Y0 - 0.99, zl0, zl1, name='cell leads')
    zbox(comp, XI_BAT[0][0] + 0.5, LEAD_X0 + 0.01, yb, yb + 1.6, zl0, zl1, name='cell leads')

    comp, _ = new_part(root, 'REF - FPC antenna')
    zbox(comp, ANT_X0, ANT_X1, ANT_Y0, ANT_Y1, Z_LID_IN, Z_LID_IN + 0.2, r=1.0, name='FPC antenna')
    # coax (1.13): off the plug, over the board's 5V edge, out past the left
    # cradle, down to the lid and up the left side (the slack coils there)
    cx0, cx1, cz = XI_X0 - 2.6, XI_X0 - 1.5, Z_XI_T + 1.35
    zbox(comp, XI_X0 + 0.45, XI_X0 + 1.55, XI_Y0 + 3.2, XI_Y0 + 9.3, cz, cz + 1.1, name='coax')
    zbox(comp, cx0, XI_X0 + 1.56, XI_Y0 + 8.2, XI_Y0 + 9.3, cz, cz + 1.1, name='coax')
    zbox(comp, cx0, cx1, XI_Y0 + 8.2, XI_Y0 + 9.3, Z_LID_IN + 0.1, cz + 0.01, name='coax')
    zbox(comp, cx0, cx1, XI_Y0 + 8.2, ANT_Y0, Z_LID_IN + 0.1, Z_LID_IN + 1.2, name='coax')

    build_switch_ref(root)


def build_switch_ref(root):
    """The two key switches (wired in parallel) and the light sensor."""
    old = find_occ(root, 'REF - Key switch and LDR')    # R1's single switch
    if old is not None:
        old.deleteMe()
    comp, _ = new_part(root, 'REF - Key switches and LDR')
    for sx in SW_XS:
        zbox(comp, sx - 3.0, sx + 3.0, SW_Y - 3.0, SW_Y + 3.0, Z_SW_BODY0, Z_SW_BODY1,
             name='tact switch 6x6x5')
        zcyl(comp, sx, SW_Y, SW_ACT_R, Z_SW_BODY1, Z_SW_ACT, name='tact switch actuator')
        for s in (1, -1):                  # the chin-side legs through the floor, then the joints
            lx, ly = sx + s*SW_LEG_Y, SW_Y - SW_LEG_X
            zcyl(comp, lx, ly, 0.45, Z_CRADLE_F, Z_SW_BODY0, name='tact switch leg')
            zbox(comp, lx - 1.0, lx + 1.0, ly - 1.0, ly + 1.0, Z_CRADLE_F - SW_SOLDER, Z_CRADLE_F,
                 name='leg solder joint')
    zcyl(comp, dot_x(), DOT_Y, LDR_R, Z_LDR0, Z_LDR1, name='LDR GL5528')


def stage_switches(app, design, root, out):
    build_switch_ref(root)


def build_display_ref(comp):
    """2.8in module, drawn header-down in its own component."""
    pcb = zbox(comp, -LCD_HW, LCD_HW, PCB_Y0, PCB_Y1, Z_PCB_B, Z_PCB_F, name='LCD PCB')
    sk = sketch_xy(comp, Z_PCB_B, 'LCD holes')
    for x, y in LCD_HOLES:
        circle_xy(sk, x, y, 1.6)
    extrude(comp, all_profiles(sk), Z_PCB_F - Z_PCB_B, CUT, bodies=[pcb])
    zbox(comp, -LCD_HW, LCD_HW, GLASS_Y0, GLASS_Y1, Z_PCB_F, -0.02, name='LCD glass')
    zbox(comp, -AA_HX, AA_HX, -AA_HY, AA_HY, -0.02, 0.0, name='LCD active area')
    zbox(comp, -23.5, 23.5, -42.5, 36.5, Z_BACKPARTS, Z_PCB_B, name='LCD back parts')
    zbox(comp, -HDR_HX - 1.27, HDR_HX + 1.27, HDR_Y - 1.27, HDR_Y + 1.27,
         Z_HDR_BODY, Z_PCB_B, name='LCD header body')
    zbox(comp, -HDR_HX - 0.4, HDR_HX + 0.4, HDR_Y - 0.4, HDR_Y + 0.4,
         Z_PIN_TIP, Z_HDR_BODY, name='LCD header pins')
    zbox(comp, -HDR_HX - 0.9, HDR_HX + 0.9, HDR_Y - 0.9, HDR_Y + 0.9,
         Z_PCB_F, Z_STUB, name='LCD header solder')


def flip_matrix():
    """Module and gasket placement: turned 180 deg about the AA centre if flipped."""
    m = adsk.core.Matrix3D.create()
    if FLIP_DISPLAY:
        m.setToRotation(math.pi, adsk.core.Vector3D.create(0, 0, 1), adsk.core.Point3D.create(0, 0, 0))
    return m


def dot_x():
    """Sensor dot = the full stop after the wordmark."""
    return WORD_X0 + WORD_PITCH*(len(WORD) - 1) + 2.45 + 0.9 + DOT_R


# ---------------------------------------------------------------- front tub

def build_tub(root):
    comp, occ = new_part(root, 'Front shell')

    # Solid slab, then the cavity: face skin, walls and the skirt the lid sits in.
    sk = sketch_xy(comp, Z_BACK, 'outline')
    outline(sk)
    body = extrude(comp, sk.profiles.item(0), Z_FACE - Z_BACK, NEW).bodies.item(0)
    body.name = 'Front shell'
    sk = sketch_xy(comp, Z_BACK, 'skirt pocket')
    outline(sk, SKIRT)
    extrude(comp, sk.profiles.item(0), Z_RIM - Z_BACK, CUT, bodies=[body])
    sk = sketch_xy(comp, Z_RIM, 'cavity')
    cavity(sk)
    extrude(comp, sk.profiles.item(0), Z_FACE_IN - Z_RIM, CUT, bodies=[body])

    # Thicker chin skin (the key pocket and guide hang off it)
    zbox(comp, -X_IN - 0.5, X_IN + 0.5, Y_IN_BOT - 0.5, -48.6, Z_CHIN_IN, Z_FACE_IN, JOIN, bodies=[body])

    # Lid screw columns, webbed into the corners, with heat-set insert holes
    extra = []
    for cx, cy in COLS:
        extra.append(zcyl(comp, cx, cy, COL_R, Z_RIM, col_top(cx, cy)))
        for x0, x1, y0, y1 in col_webs(cx, cy, 0.5):
            extra.append(zbox(comp, x0, x1, y0, y1, Z_RIM, col_top(cx, cy)))
    combine(comp, body, extra)
    # seal bead along the rim: a continuous ridge the lid gasket is squeezed onto
    sk = sketch_xy(comp, Z_RIM - BEAD_H, 'seal bead')
    cavity(sk, 0.3)
    cavity(sk, 0.3 + BEAD_W)
    extrude(comp, [p for p in sk.profiles if p.profileLoops.count == 2][0], BEAD_H + 0.01, JOIN,
            bodies=[body])
    sk = sketch_xy(comp, Z_RIM, 'insert holes')
    for cx, cy in COLS:
        circle_xy(sk, cx, cy, INSERT_R)
    extrude(comp, all_profiles(sk), INSERT_D, CUT, bodies=[body])

    # Side ribs locate the module in X; end ribs locate the gasket in Y.
    for y in SIDE_RIB_Y:
        for s in (1, -1):
            zbox(comp, min(s*RIB_X, s*(X_IN + 0.5)), max(s*RIB_X, s*(X_IN + 0.5)), y - 1.5, y + 1.5,
                 Z_PCB_B, Z_FACE_IN + 0.01, JOIN, bodies=[body])
    for sy in (1, -1):
        for sx in (1, -1):
            zbox(comp, min(sx*12.0, sx*24.0), max(sx*12.0, sx*24.0),
                 min(sy*GK_HY, sy*(GK_HY + 0.8)), max(sy*GK_HY, sy*(GK_HY + 0.8)),
                 Z_GK_LIP, Z_FACE_IN + 0.01, JOIN, bodies=[body])

    # Snap arms over the module PCB edges, at y = +-ARM_Y (both orientations)
    for sy in (1, -1):
        for sx in (1, -1):
            x0, x1 = sorted((sx*ARM_X0, sx*(ARM_X0 + ARM_T)))
            zbox(comp, x0, x1, sy*ARM_Y - ARM_W/2, sy*ARM_Y + ARM_W/2, Z_ARM_TIP, Z_FACE_IN + 0.01,
                 JOIN, bodies=[body])
    for sy in (1, -1):
        sk = sketch_on(comp, comp.xZConstructionPlane, sy*ARM_Y, 'y', 'arm hooks')
        for sx in (1, -1):
            poly_world(sk, [P(sx*ARM_X0, sy*ARM_Y, Z_PCB_B - 0.05),
                            P(sx*(ARM_X0 - ARM_HOOK), sy*ARM_Y, Z_PCB_B - 0.05),
                            P(sx*(ARM_X0 - ARM_HOOK), sy*ARM_Y, Z_PCB_B - 0.25),
                            P(sx*ARM_X0, sy*ARM_Y, Z_ARM_TIP)])
        extrude_sym(comp, all_profiles(sk), ARM_W, JOIN, bodies=[body])

    # Lens pocket, and the window and sensor dot through its floor
    zbox(comp, -LENS_HX - LENS_CLR, LENS_HX + LENS_CLR, LENS_Y0 - LENS_CLR, LENS_Y1 + LENS_CLR,
         Z_LENS_SEAT, Z_FACE + 0.5, CUT, r=LENS_R + LENS_CLR, bodies=[body])
    zbox(comp, -AP_HX, AP_HX, -AP_HY, AP_HY, Z_FACE_IN - 0.5, Z_LENS_SEAT + 0.5, CUT, r=AP_R, bodies=[body])

    # Chin key: pocket in the face, stem guide behind it, ears for the carrier
    z_pocket = Z_FACE - POCKET_D
    sk = sketch_xy(comp, z_pocket, 'key pocket')
    rrect(sk, -KEY_HX - KEY_CLR, KEY_Y0 - KEY_CLR, KEY_HX + KEY_CLR, KEY_Y1 + KEY_CLR, KEY_R + KEY_CLR)
    extrude(comp, sk.profiles.item(0), POCKET_D + 0.5, CUT, bodies=[body])
    gx, gy = GX, GY
    zbox(comp, -gx, gx, STEM_YC - gy, STEM_YC + gy, Z_GUIDE_END, Z_CHIN_IN + 0.01, JOIN, r=1.5, bodies=[body])
    for s in (1, -1):
        zcyl(comp, s*EAR_X, STEM_YC, EAR_R, Z_EAR_END, Z_CHIN_IN + 0.01, JOIN, bodies=[body])
        if EAR_X - EAR_R > gx - 0.5:       # bridge an ear that stands clear of the guide
            zbox(comp, min(s*(gx - 0.5), s*EAR_X), max(s*(gx - 0.5), s*EAR_X), STEM_YC - 1.6, STEM_YC + 1.6,
                 Z_EAR_END, Z_CHIN_IN + 0.01, JOIN, bodies=[body])
    sk = sketch_xy(comp, Z_GUIDE_END - 1.0, 'stem slot')
    rrect(sk, -STEM_HX - STEM_CLR, STEM_YC - STEM_HY - STEM_CLR, STEM_HX + STEM_CLR,
          STEM_YC + STEM_HY + STEM_CLR, 1.0)
    extrude(comp, sk.profiles.item(0), z_pocket - Z_GUIDE_END + 1.5, CUT, bodies=[body])
    for s in (1, -1):                      # grooves the stem's snap bumps ride in
        y = STEM_YC + s*(STEM_HY + STEM_CLR)
        y0, y1 = sorted((y - s*0.05, y + s*(BUMP + 0.15)))
        zbox(comp, -5.6, 5.6, y0, y1, Z_GUIDE_END - 0.1, Z_STEM_TIP + 1.05, CUT, bodies=[body])
    sk = sketch_xy(comp, Z_EAR_END, 'carrier screw pilots')
    for s in (1, -1):
        circle_xy(sk, s*EAR_X, STEM_YC, 0.85)
    extrude(comp, all_profiles(sk), 5.0, CUT, bodies=[body])

    # Sensor dot: a hole in the pocket floor (under the lens), the LDR in a cup behind it
    zcyl(comp, dot_x(), DOT_Y, CUP_R, Z_LDR0 - 0.4, Z_FACE_IN + 0.01, JOIN, bodies=[body])
    zcyl(comp, dot_x(), DOT_Y, LDR_R + 0.05, Z_LDR0 - 0.5, Z_FACE_IN, CUT, bodies=[body])
    zcyl(comp, dot_x(), DOT_Y, DOT_R + 0.05, Z_FACE_IN - 0.5, Z_LENS_SEAT + 0.5, CUT, bodies=[body])
    floor = planar_face(body, 'z', Z_LENS_SEAT, True)
    loops = sorted((lp for lp in floor.loops if not lp.isOuter),
                   key=lambda lp: lp.boundingBox.maxPoint.x - lp.boundingBox.minPoint.x)
    chamfer(comp, list(loops[-1].edges), AP_BEVEL)      # window
    floor = planar_face(body, 'z', Z_LENS_SEAT, True)
    loops = sorted((lp for lp in floor.loops if not lp.isOuter),
                   key=lambda lp: lp.boundingBox.maxPoint.x - lp.boundingBox.minPoint.x)
    chamfer(comp, list(loops[0].edges), 0.4)             # dot
    chamfer(comp, loop_edges(planar_face(body, 'z', Z_FACE, True), outer=False), 0.3)

    # USB-C: tunnel through the bottom wall, in line with the XIAO's receptacle
    y0, y1 = Y_BOT - 1.0, Y_IN_BOT + 0.5
    sk = sketch_on(comp, comp.xZConstructionPlane, (y0 + y1)/2, 'y', 'usb tunnel')
    rrect_mapped(sk, lambda u, v: P(u, (y0 + y1)/2, v), USB_X - TUN_HW, USB_Z - TUN_HH,
                 USB_X + TUN_HW, USB_Z + TUN_HH, TUN_R)
    extrude_sym(comp, sk.profiles.item(0), y1 - y0, CUT, bodies=[body])

    # Wordmark, debossed in the pocket floor and read through the lens (the dot is its full stop)
    sk = sketch_xy(comp, Z_LENS_SEAT, 'wordmark')
    for i, ch in enumerate(WORD):
        x0 = WORD_X0 + i*WORD_PITCH
        ti = sk.sketchTexts.createInput2(ch, WORD_CAP*MM)
        ti.setAsMultiLine(P(x0, WORD_BASE - 0.6), P(x0 + 4.0, WORD_BASE + WORD_CAP + 0.6),
                          adsk.core.HorizontalAlignments.LeftHorizontalAlignment,
                          adsk.core.VerticalAlignments.MiddleVerticalAlignment, 0)
        ti.fontName = 'DIN Alternate'
        ti.textStyle = adsk.fusion.TextStyles.TextStyleBold
        t = sk.sketchTexts.add(ti)
        try:
            extrude(comp, t, WORD_DEPTH, CUT, up=False, bodies=[body])
        except Exception:
            extrude(comp, t, WORD_DEPTH, CUT, up=False, bodies=[body])

    # Edges: soft face edge, small round on the skirt end
    fillet(comp, loop_edges(planar_face(body, 'z', Z_FACE, True), outer=True), FACE_EDGE_R)
    fillet(comp, loop_edges(planar_face(body, 'z', Z_BACK, False), outer=True), 0.6)
    return comp, occ, body


# ---------------------------------------------------------------- entry point

def build(stages):
    app = adsk.core.Application.get()
    design = adsk.fusion.Design.cast(app.activeProduct)
    root = design.rootComponent
    mark = root.attributes.itemByName('raah', 'design')
    if mark is None or mark.value != 'enclosure-r1':
        raise RuntimeError('active design is not marked raah/design=enclosure-r1; nothing built')
    if design.designType != adsk.fusion.DesignTypes.DirectDesignType:
        design.designType = adsk.fusion.DesignTypes.DirectDesignType
    out = {}
    for st in stages:
        try:
            if st == 'refs':
                build_refs(app, design, root)
            elif st == 'tub':
                comp, occ, body = build_tub(root)
                print('tub', bbox_str(body), '%.0f mm3' % (body.volume*1000))
            else:
                globals()['stage_' + st](app, design, root, out)
        except Exception:
            print('FAILED at stage', st)
            print(traceback.format_exc())
            break
    return out


def view(eye, up=(0, 1, 0), target=(0, -6, -8), dist=260.0, fit=True):
    """Point the camera from direction eye (device frame) at target."""
    app = adsk.core.Application.get()
    vp = app.activeViewport
    cam = vp.camera
    n = math.sqrt(sum(c*c for c in eye))
    cam.target = P(*target)
    cam.eye = P(*[t + dist*c/n for t, c in zip(target, eye)])
    cam.upVector = adsk.core.Vector3D.create(*up)
    cam.isSmoothTransition = False
    cam.cameraType = adsk.core.CameraTypes.PerspectiveCameraType
    cam.perspectiveAngle = math.radians(18)
    vp.camera = cam
    if fit:
        vp.fit()
    vp.refresh()


def show_only(root, names=None, hide=()):
    """Light up only the named components (all when names is None)."""
    for i in range(root.occurrences.count):
        o = root.occurrences.item(i)
        n = o.component.name
        o.isLightBulbOn = (names is None or any(n.startswith(k) for k in names)) and not any(
            n.startswith(k) for k in hide)


# ---------------------------------------------------------------- small front parts

def build_display_gasket(root):
    """TPU frame that seals on the glass and holds it in Y; flips with the module."""
    comp, _ = new_part(root, 'Display gasket', flip_matrix())
    z0 = Z_FACE_IN - GK_T
    g = zbox(comp, -GK_HX, GK_HX, -GK_HY, GK_HY, z0, Z_FACE_IN, name='Display gasket')
    zbox(comp, -GK_WIN_HX, GK_WIN_HX, -GK_WIN_HY, GK_WIN_HY, z0 - 0.1, Z_FACE_IN + 0.1, CUT, r=0.6,
         bodies=[g])
    zbox(comp, -GK_HX, GK_HX, GLASS_Y1 + 0.15, GK_HY, Z_GK_LIP, z0 + 0.01, JOIN, bodies=[g])
    zbox(comp, -GK_HX, GK_HX, -GK_HY, GLASS_Y0 - 0.15, Z_GK_LIP, z0 + 0.01, JOIN, bodies=[g])


def stage_front_parts(app, design, root, out):
    build_display_gasket(root)

    # Key: flat yellow cap, hollow stem with snap bumps
    comp, _ = new_part(root, 'Key')
    zk0 = Z_FACE - POCKET_D + KEY_LIFT
    key = zbox(comp, -KEY_HX, KEY_HX, KEY_Y0, KEY_Y1, zk0, zk0 + KEY_T, r=KEY_R, name='Key')
    fillet(comp, loop_edges(planar_face(key, 'z', zk0 + KEY_T, True)), 1.0)
    chamfer(comp, loop_edges(planar_face(key, 'z', zk0, False)), 0.3)
    z_tip = Z_STEM_TIP
    zbox(comp, -STEM_HX, STEM_HX, STEM_YC - STEM_HY, STEM_YC + STEM_HY, z_tip, zk0 + 0.01, JOIN, r=0.8,
         bodies=[key])
    zbox(comp, -STEM_HX + 1.0, STEM_HX - 1.0, STEM_YC - STEM_HY + 1.0, STEM_YC + STEM_HY - 1.0,
         z_tip - 0.1, zk0 - 1.0, CUT, r=0.4, bodies=[key])
    sk = sketch_on(comp, comp.yZConstructionPlane, 0.0, 'x', 'stem bumps')
    for s in (1, -1):
        y = STEM_YC + s*STEM_HY
        poly_world(sk, [P(0, y, z_tip + 0.2), P(0, y + s*BUMP, z_tip + 0.5),
                        P(0, y + s*BUMP, z_tip + 0.9), P(0, y, z_tip + 1.0)])
    extrude_sym(comp, all_profiles(sk), 10.0, JOIN, bodies=[key])
    for sx in SW_XS:                       # a rib across the hollow stem, a nub over each switch
        zbox(comp, sx - 1.0, sx + 1.0, STEM_YC - STEM_HY + 0.9, STEM_YC + STEM_HY - 0.9, z_tip, zk0 - 0.99,
             JOIN, bodies=[comp.bRepBodies.item(0)])
        zcyl(comp, sx, SW_Y, NUB_R, Z_GUIDE_END + NUB_GAP, z_tip + 0.01, JOIN, bodies=[comp.bRepBodies.item(0)])

    # Membrane (TPU 95A, printed 0.6): seals the guide; the nubs press the switches through it
    comp, _ = new_part(root, 'Key membrane')
    gx, gy = GX, GY
    zbox(comp, -MEMB_HX, MEMB_HX, STEM_YC - gy, STEM_YC + gy, Z_GUIDE_END - MEMB_T, Z_GUIDE_END, r=1.2,
         name='Key membrane')

    # Switch carrier: clamps the membrane, cradles the two switches. Each switch
    # drops in from the face side, legs through the floor, bent flat and wired.
    # A pad over the XIAO's USB-C keeps the board down on its tape.
    comp, _ = new_part(root, 'Switch carrier')
    zbox(comp, -EAR_X - 1.7, EAR_X + 1.7, STEM_YC - gy, STEM_YC + gy, Z_CARRIER_B, Z_EAR_END,
         r=3.0, name='Switch carrier')                  # big corners clear the shell's inner fillets
    zbox(comp, USB_X - 3.0, USB_X + 3.0, XI_Y0, XI_Y0 + 5.0, Z_XI_T + USB_H + 0.1, Z_CARRIER_B + 0.01,
         JOIN, bodies=[comp.bRepBodies.item(0)])
    px = py = SW_LEG_X + 0.35                          # square pocket: the switch fits either way round
    for sx in SW_XS:
        zbox(comp, sx - px - CRADLE_WALL, sx + px + CRADLE_WALL, SW_Y - py - CRADLE_WALL,
             SW_Y + SW_BODY/2 + 0.1, Z_CRADLE_F, Z_CARRIER_B + 0.01, JOIN, r=0.8,
             bodies=[comp.bRepBodies.item(0)])
    c = comp.bRepBodies.item(0)
    hx, hy = STEM_HX + STEM_CLR + MEMB_FREE, STEM_HY + STEM_CLR + 0.3
    zbox(comp, -hx, hx, STEM_YC - hy, STEM_YC + hy, Z_RECESS, Z_EAR_END + 0.1, CUT, r=1.0, bodies=[c])
    for sx in SW_XS:
        zbox(comp, sx - px, sx + px, SW_Y - py, SW_Y + py + 1.0, Z_SW_BODY0, Z_EAR_END + 0.1, CUT, r=0.3,
             bodies=[comp.bRepBodies.item(0)])
    sk = sketch_xy(comp, Z_CRADLE_F - 0.5, 'carrier holes')
    for s in (1, -1):
        circle_xy(sk, s*EAR_X, STEM_YC, 1.15)
    for sx in SW_XS:                                   # chin-side legs only, for either turn
        for lx, ly in ((SW_LEG_Y, SW_LEG_X), (SW_LEG_X, SW_LEG_Y)):
            for s in (1, -1):
                circle_xy(sk, sx + s*lx, SW_Y - ly, 0.6)
    extrude(comp, all_profiles(sk), 8.0, CUT, bodies=[comp.bRepBodies.item(0)])

    # Lens (cast acrylic, laser-cut) on its VHB ring
    comp, _ = new_part(root, 'Lens')
    lens = zbox(comp, -LENS_HX, LENS_HX, LENS_Y0, LENS_Y1, Z_LENS_SEAT + VHB_T, Z_FACE, r=LENS_R, name='Lens')
    chamfer(comp, loop_edges(planar_face(lens, 'z', Z_FACE, True)), 0.3)
    comp, _ = new_part(root, 'Lens tape (VHB)')
    sk = sketch_xy(comp, Z_LENS_SEAT, 'vhb ring')
    rrect(sk, -LENS_HX, LENS_Y0, LENS_HX, LENS_Y1, LENS_R)
    rrect(sk, -LENS_HX + VHB_W, LENS_Y0 + VHB_W, LENS_HX - VHB_W, LENS_Y1 - VHB_W, LENS_R - VHB_W)
    ring = [p for p in sk.profiles if p.profileLoops.count == 2][0]
    extrude(comp, ring, VHB_T, NEW).bodies.item(0).name = 'Lens tape (VHB)'

    # USB-C plug (TPU 95A): two sealing ribs grip the tunnel, a flange covers
    # its mouth, the tab lies flat along the bottom face.
    comp, _ = new_part(root, 'USB plug')
    zb, zt = USB_Z - TUN_HH, USB_Z + TUN_HH
    y1 = Y_IN_BOT - 0.1                    # 0.4 short of the receptacle

    def plug_section(y_mid, length, grow, r_extra=0.0, x0=None, x1=None, z0=None, z1=None, op=JOIN):
        sk = sketch_on(comp, comp.xZConstructionPlane, y_mid, 'y', 'plug section')
        rrect_mapped(sk, lambda u, v: P(u, y_mid, v),
                     USB_X - TUN_HW - grow if x0 is None else x0, zb - grow if z0 is None else z0,
                     USB_X + TUN_HW + grow if x1 is None else x1, zt + grow if z1 is None else z1,
                     TUN_R + grow + r_extra)
        bodies = None if op == NEW else [comp.bRepBodies.item(0)]
        return extrude_sym(comp, sk.profiles.item(0), length, op, bodies=bodies)

    plug_section((Y_BOT + y1)/2, y1 - Y_BOT, -0.05, op=NEW).bodies.item(0).name = 'USB plug'
    for yr in (Y_BOT + 0.7, Y_BOT + 1.7):
        plug_section(yr, 0.6, 0.3)
    plug_section(Y_BOT - 0.6, 1.2, 1.5)
    plug_section(Y_BOT - 0.6, 1.2, 0, x0=USB_X + TUN_HW, x1=USB_X + TUN_HW + 7.0, z0=zb - 1.5, z1=zb + 1.5,
                 r_extra=-TUN_R + 1.2)
    zbox(comp, USB_X - TUN_HW + 1.2, USB_X + TUN_HW - 1.2, Y_BOT + 0.6, y1 + 0.1,
         zb + 1.2, zt - 1.2, CUT, r=1.5, bodies=[comp.bRepBodies.item(0)])


# ---------------------------------------------------------------- lid (back, the chassis)

def lid_outline(sk, inset=0.0):
    k = SKIRT + 0.1 + inset
    rrect(sk, -X_OUT + k, Y_BOT + k, X_OUT - k, Y_TOP - k, R_OUT - k)


def stage_lid(app, design, root, out):
    comp, _ = new_part(root, 'Lid')
    sk = sketch_xy(comp, Z_BACK, 'lid')
    lid_outline(sk)
    lid = extrude(comp, sk.profiles.item(0), Z_LID_IN - Z_BACK, NEW).bodies.item(0)
    lid.name = 'Lid'
    fillet(comp, loop_edges(planar_face(lid, 'z', Z_BACK, False)), 0.6)

    # XIAO: four posts under the PCB (foam tape on them; clear of the castellations
    # and the bottom pads), corner guides, stops in front of the USB end and a wall
    # behind the far end. The pad on the switch carrier keeps it down. 2.5 under
    # the board for the cell leads to reach the BAT pads.
    g = 0.15
    for x, y in ((XI_X0 + 3.5, XI_Y0 + 1.0), (XI_X1 - 3.5, XI_Y0 + 1.0),
                 (XI_X0 + 1.0, XI_Y1 - 1.0), (XI_X1 - 1.0, XI_Y1 - 1.0)):
        zbox(comp, x - 0.9, x + 0.9, y - 0.8, y + 0.8, Z_LID_IN - 0.01, Z_XI_B, JOIN, bodies=[lid])
    for x0, x1 in ((XI_X0 - g - 1.0, XI_X0 - g), (XI_X1 + g, XI_X1 + g + 1.0)):
        for y0, y1 in ((XI_Y0 - g - 0.8, XI_Y0 + 2.0), (XI_Y1 - 2.0, XI_Y1 + g + 1.0)):
            zbox(comp, x0, x1, y0, y1, Z_LID_IN - 0.01, Z_XI_T - 0.3, JOIN, bodies=[lid])
    for x0, x1 in ((XI_X0 - g - 1.0, USB_X - TUN_HW - 0.3), (USB_X + TUN_HW + 0.3, XI_X1 + g + 1.0)):
        zbox(comp, x0, x1, XI_Y0 - g - 0.8, XI_Y0 - g, Z_LID_IN - 0.01, Z_XI_T, JOIN, bodies=[lid])
    zbox(comp, XI_X0 + 1.0, XI_X1 - 1.0, XI_Y1 + g, XI_Y1 + g + 1.0, Z_LID_IN - 0.01, Z_XI_T + 0.6, JOIN,
         bodies=[lid])

    # Cell: low side walls and end stops (foam pad under it); the antenna sticks on the plate
    for s in (1, -1):
        x0, x1 = sorted((s*(BAT_HX + 0.3), s*(BAT_HX + 1.3)))
        zbox(comp, x0, x1, BAT_Y0 + 6.0, BAT_Y1 - 6.0, Z_LID_IN - 0.01, Z_BAT_B + 2.0, JOIN, bodies=[lid])
        x0, x1 = sorted((s*(BAT_HX - 6.0), s*(BAT_HX + 1.3)))
        zbox(comp, x0, x1, BAT_Y1 + 0.3, BAT_Y1 + 1.1, Z_LID_IN - 0.01, Z_BAT_B + 2.5, JOIN, bodies=[lid])
        x0, x1 = sorted((s*(BAT_HX - 1.5), s*(BAT_HX + 1.3)))   # the leads pass inside these
        zbox(comp, x0, x1, BAT_Y0 - 1.1, BAT_Y0 - 0.3, Z_LID_IN - 0.01, Z_BAT_B + 2.5, JOIN, bodies=[lid])

    # Screw holes with a spot face; countersunk holes for the tab screws
    sk = sketch_xy(comp, Z_BACK - 0.5, 'lid screw holes')
    for cx, cy in COLS:
        circle_xy(sk, cx, cy, 1.2)
    for x, y in TAB_SCREWS:
        circle_xy(sk, x, y, 1.45)
    extrude(comp, all_profiles(sk), 4.0, CUT, bodies=[lid])
    sk = sketch_xy(comp, Z_BACK - 0.5, 'lid spot faces')
    for cx, cy in COLS:
        circle_xy(sk, cx, cy, 2.3)
    extrude(comp, all_profiles(sk), 1.4, CUT, bodies=[lid])
    for x, y in TAB_SCREWS:
        cone(comp, lid, x, y, Z_LID_IN, 2.6, 1.45)
    # pressure vent: 1.5 hole under an 8 mm adhesive ePTFE vent sticker
    zcyl(comp, VENT[0], VENT[1], 0.75, Z_BACK - 0.5, Z_LID_IN + 0.5, CUT, bodies=[lid])
    zcyl(comp, VENT[0], VENT[1], 4.3, Z_LID_IN - 0.3, Z_LID_IN + 0.5, CUT, bodies=[lid])

    # Lid gasket (TPU 95A, printed 0.8, set to 0.7; the bead bites 0.3 more)
    comp, _ = new_part(root, 'Lid gasket')
    sk = sketch_xy(comp, Z_LID_IN, 'lid gasket')
    lid_outline(sk)
    cavity(sk)
    ring = max(sk.profiles, key=lambda p: p.areaProperties().area if p.profileLoops.count > 1 else 0)
    gk = extrude(comp, ring, GASKET_T, NEW).bodies.item(0)
    gk.name = 'Lid gasket'
    for cx, cy in COLS:
        zcyl(comp, cx, cy, COL_R, Z_LID_IN, Z_LID_IN + GASKET_T, JOIN, bodies=[comp.bRepBodies.item(0)])
        for x0, x1, y0, y1 in col_webs(cx, cy, 0.3):
            zbox(comp, x0, x1, y0, y1, Z_LID_IN, Z_LID_IN + GASKET_T, JOIN, bodies=[comp.bRepBodies.item(0)])
    gk = comp.bRepBodies.item(0)
    sk = sketch_xy(comp, Z_LID_IN - 0.5, 'gasket trim')
    lid_outline(sk)
    rrect(sk, -X_OUT - 5, Y_BOT - 5, X_OUT + 5, Y_TOP + 5)
    trim = [p for p in sk.profiles if p.profileLoops.count == 2][0]
    tool = extrude(comp, trim, 2.0, NEW).bodies.item(0)
    combine(comp, gk, [tool], CUT)
    gk = comp.bRepBodies.item(0)
    sk = sketch_xy(comp, Z_LID_IN - 0.5, 'gasket screw holes')
    for cx, cy in COLS:
        circle_xy(sk, cx, cy, 1.3)
    extrude(comp, all_profiles(sk), 2.0, CUT, bodies=[gk])
    comp.bRepBodies.item(0).name = 'Lid gasket'


def cone(comp, body, x, y, z_top, r_top, r_bot):
    """90 deg countersink opening at z_top (cut downward)."""
    sk = sketch_on(comp, comp.xZConstructionPlane, y, 'y', 'countersink')
    h = r_top - r_bot
    poly_world(sk, [P(x, y, z_top + 0.01), P(x + r_top, y, z_top + 0.01), P(x + r_bot, y, z_top - h),
                    P(x, y, z_top - h)])
    axis = sk.sketchCurves.sketchLines.addByTwoPoints(sk.modelToSketchSpace(P(x, y, z_top + 1)),
                                                      sk.modelToSketchSpace(P(x, y, z_top - 3)))
    axis.isConstruction = True
    ri = comp.features.revolveFeatures.createInput(sk.profiles.item(0), axis, CUT)
    ri.setAngleExtent(False, adsk.core.ValueInput.createByReal(2*math.pi))
    ri.participantBodies = [body]
    comp.features.revolveFeatures.add(ri)


# ---------------------------------------------------------------- quarter-turn tab

def sector_ring(sk, cx, cy, r0, r1, a0, a1):
    """Annular sector on an XY sketch, angles in degrees."""
    pts = []
    n = 12
    for i in range(n + 1):
        a = math.radians(a0 + (a1 - a0)*i/n)
        pts.append((cx + r1*math.cos(a), cy + r1*math.sin(a)))
    for i in range(n, -1, -1):
        a = math.radians(a0 + (a1 - a0)*i/n)
        pts.append((cx + r0*math.cos(a), cy + r0*math.sin(a)))
    poly_xy(sk, pts)


def stage_tab(app, design, root, out):
    cx, cy = TAB_C
    comp, _ = new_part(root, 'Tab gasket')
    zb = Z_BACK - TAB_GASKET
    tg = zcyl(comp, cx, cy, TAB_BASE_R - 0.5, zb, Z_BACK, name='Tab gasket')
    sk = sketch_xy(comp, zb - 0.5, 'tab gasket holes')
    for x, y in TAB_SCREWS:
        circle_xy(sk, x, y, 1.45)
    extrude(comp, all_profiles(sk), 2.0, CUT, bodies=[tg])

    comp, _ = new_part(root, 'Quarter-turn tab')
    z1 = zb
    z0 = z1 - TAB_BASE_T
    tab = zcyl(comp, cx, cy, TAB_BASE_R, z0, z1, name='Quarter-turn tab')
    zn = z0 - TAB_NECK_H
    zcyl(comp, cx, cy, TAB_NECK_R, zn, z0 + 0.01, JOIN, bodies=[tab])
    sk = sketch_xy(comp, zn - TAB_EAR_T, 'tab ears')
    for a in (0.0, 180.0):
        sector_ring(sk, cx, cy, TAB_NECK_R - 1.0, TAB_EAR_R, a - TAB_EAR_DEG/2, a + TAB_EAR_DEG/2)
    circle_xy(sk, cx, cy, TAB_NECK_R - 0.5)
    extrude(comp, all_profiles(sk), TAB_EAR_T + 0.01, JOIN, bodies=[tab])
    chamfer(comp, loop_edges(planar_face(tab, 'z', zn - TAB_EAR_T, False)), 0.4)
    fillet(comp, loop_edges(planar_face(tab, 'z', z1, True)), 0.4)
    sk = sketch_xy(comp, z1 + 0.5, 'tab inserts')
    for x, y in TAB_SCREWS:
        circle_xy(sk, x, y, 1.6)
    extrude(comp, all_profiles(sk), 5.5, CUT, up=False, bodies=[tab])
    out['tab_z'] = (zn - TAB_EAR_T, zn, z0, z1)


# ---------------------------------------------------------------- bar mount

HINGE = (-12.0, Z_BACK - 20.7)             # tilt axis (along X) at device (y, z)
TILT_DEG = 60.0                            # face 30 deg off horizontal: up, toward the rider
BAR_D = 32.0                               # hinge to bar centre
BORE_R, RING_R, RING_X = 15.9, 20.4, (0.0, 16.0)
SPLIT = 0.5                                # half gap between the clamp halves
STAR_N, STAR_RO, STAR_RI, STAR_H = 24, 8.0, 7.0, 2.0
RCV_R, Z_RCV_F, Z_RCV_B = 18.0, Z_BACK - 2.3, Z_BACK - 9.1
LIP_T, BORE_LIP, CHAMBER_R = 2.2, 10.7, 14.4
M5_R, M5_HEAD_R, M5_NUT_R, M4_R, M4_NUT_R = 2.75, 4.4, 4.7, 2.2, 4.1
FLANGE_Z = 23.4


def xsketch(comp, x, name):
    return sketch_on(comp, comp.yZConstructionPlane, x, 'x', name)


def xcyl(comp, y, z, r, x0, x1, op=NEW, bodies=None, name=None):
    xm = (x0 + x1)/2
    sk = xsketch(comp, xm, name or 'x cylinder')
    circle_world(sk, P(xm, y, z), r)
    f = extrude_sym(comp, sk.profiles.item(0), x1 - x0, op, bodies=bodies)
    if op == NEW:
        b = f.bodies.item(0)
        if name:
            b.name = name
        return b
    return f


def xpoly(comp, yz, x0, x1, op=NEW, bodies=None, name=None):
    xm = (x0 + x1)/2
    sk = xsketch(comp, xm, name or 'x prism')
    poly_world(sk, [P(xm, y, z) for y, z in yz])
    f = extrude_sym(comp, sk.profiles.item(0), x1 - x0, op, bodies=bodies)
    if op == NEW:
        b = f.bodies.item(0)
        if name:
            b.name = name
        return b
    return f


def star_yz(cy, cz, ro, ri, n=STAR_N):
    pts = []
    for i in range(2*n):
        a = math.pi*i/n
        r = ro if i % 2 == 0 else ri
        pts.append((cy + r*math.cos(a), cz + r*math.sin(a)))
    return pts


def clamp_matrix():
    """Clamp-local frame: X = tilt axis, origin on the hinge, bar at y = -BAR_D."""
    m = adsk.core.Matrix3D.create()
    m.setToRotation(math.radians(TILT_DEG), adsk.core.Vector3D.create(1, 0, 0),
                    adsk.core.Point3D.create(0, 0, 0))
    m.translation = adsk.core.Vector3D.create(0, HINGE[0]*MM, HINGE[1]*MM)
    return m


def stage_mount(app, design, root, out):
    cx, cy = TAB_C
    hy, hz = HINGE

    # Receiver: quarter-turn socket on an arm that carries the spline boss.
    comp, _ = new_part(root, 'Receiver arm')
    r = zcyl(comp, cx, cy, RCV_R, Z_RCV_B, Z_RCV_F, name='Receiver arm')
    fillet(comp, loop_edges(planar_face(r, 'z', Z_RCV_F, True)), 1.0)
    zcyl(comp, cx, cy, BORE_LIP, Z_RCV_F - LIP_T - 0.01, Z_RCV_F + 0.1, CUT, bodies=[r])
    zcyl(comp, cx, cy, CHAMBER_R, Z_RCV_F - LIP_T - 2.2, Z_RCV_F - LIP_T, CUT, bodies=[r])
    sk = sketch_xy(comp, Z_RCV_F - LIP_T - 0.01, 'entry slots')
    for a in (90.0, 270.0):
        sector_ring(sk, cx, cy, BORE_LIP - 0.2, CHAMBER_R, a - TAB_EAR_DEG/2 - 2.5, a + TAB_EAR_DEG/2 + 2.5)
    extrude(comp, all_profiles(sk), LIP_T + 0.2, CUT, bodies=[r])
    sk = sketch_xy(comp, Z_RCV_F - LIP_T - 2.21, 'turn stops')
    for a0, a1 in ((305.0, 322.0), (125.0, 142.0)):
        sector_ring(sk, cx, cy, BORE_LIP, CHAMBER_R + 0.2, a0, a1)
    extrude(comp, all_profiles(sk), 2.22, JOIN, bodies=[r])
    chamfer(comp, loop_edges(planar_face(r, 'z', Z_RCV_F, True), outer=False), 0.4)
    # arm down to the hinge, the spline boss and the M5 counterbore
    xcyl(comp, hy, hz, 10.0, -6.0, 0.0, JOIN, bodies=[r])
    xpoly(comp, [(hy - 10, hz), (hy + 10, hz), (hy + 20, Z_RCV_B + 2.4), (hy - 4, Z_RCV_B + 2.4)],
          -6.0, 0.0, JOIN, bodies=[r])
    xpoly(comp, star_yz(hy, hz, STAR_RO, STAR_RI), -0.01, STAR_H, JOIN, bodies=[r])
    xcyl(comp, hy, hz, M5_R, -7.0, STAR_H + 1.0, CUT, bodies=[r])
    xcyl(comp, hy, hz, M5_HEAD_R, -7.0, -3.0, CUT, bodies=[r])

    m = clamp_matrix()
    by = -BAR_D

    def half(name, upper):
        comp, _ = new_part(root, name, m)
        b = xcyl(comp, by, 0.0, RING_R, *RING_X, name=name)
        sgn = 1 if upper else -1
        y0, y1 = sorted((by + sgn*SPLIT, by + sgn*7.0))
        for s in (1, -1):
            zbox_local = xpoly(comp, [(y0, s*16.0), (y1, s*16.0), (y1, s*27.6), (y0, s*27.6)], *RING_X)
            combine(comp, b, [zbox_local])
        xcyl(comp, by, 0.0, BORE_R, RING_X[0] - 1, RING_X[1] + 1, CUT, bodies=[b])
        # keep this side of the split
        far = by - sgn*40
        xpoly(comp, [(by + sgn*SPLIT, -40), (far, -40), (far, 40), (by + sgn*SPLIT, 40)],
              RING_X[0] - 1, RING_X[1] + 1, CUT, bodies=[b])
        # M4 bolts through the flanges (along local y)
        xm = (RING_X[0] + RING_X[1])/2
        for s in (1, -1):
            sk = sketch_on(comp, comp.xZConstructionPlane, by, 'y', 'm4')
            circle_world(sk, P(xm, by, s*FLANGE_Z), M4_R)
            extrude_sym(comp, sk.profiles.item(0), 30.0, CUT, bodies=[b])
            if not upper:
                sk = sketch_on(comp, comp.xZConstructionPlane, by - 7.0, 'y', 'm4 nut')
                pts = [P(xm + M4_NUT_R*math.cos(math.pi/3*i), by - 7.0, s*FLANGE_Z + M4_NUT_R*math.sin(math.pi/3*i))
                       for i in range(6)]
                poly_world(sk, pts)
                extrude_sym(comp, sk.profiles.item(0), 2*3.2, CUT, bodies=[b])
        return comp, b

    comp, up = half('Clamp upper', True)
    # hinge lug with the star socket
    xpoly(comp, [(-14.0, -7.0), (0.0, -7.0), (0.0, 7.0), (-14.0, 7.0)], 0.0, 6.0, JOIN,
          bodies=[comp.bRepBodies.item(0)])
    xcyl(comp, 0.0, 0.0, 10.0, 0.0, 6.0, JOIN, bodies=[comp.bRepBodies.item(0)])
    up = comp.bRepBodies.item(0)
    xpoly(comp, star_yz(0.0, 0.0, STAR_RO + 0.15, STAR_RI + 0.15), -0.1, STAR_H + 0.2, CUT, bodies=[up])
    xcyl(comp, 0.0, 0.0, M5_R, -1.0, 7.0, CUT, bodies=[up])
    half('Clamp lower', False)

    # Tilt knob (M5 nut inside), scalloped for gloves
    comp, _ = new_part(root, 'Tilt knob', m)
    k = xcyl(comp, 0.0, 0.0, 9.0, 6.2, 14.0, name='Tilt knob')
    for i in range(8):
        a = 2*math.pi*i/8
        xcyl(comp, 10.2*math.cos(a), 10.2*math.sin(a), 2.0, 5.0, 15.0, CUT, bodies=[k])
    xcyl(comp, 0.0, 0.0, M5_R, 5.0, 15.0, CUT, bodies=[k])
    xpoly(comp, [(M5_NUT_R*math.cos(math.pi/3*i), M5_NUT_R*math.sin(math.pi/3*i)) for i in range(6)],
          10.0, 14.1, CUT, bodies=[k])

    # TPU shims for 25.4 and 22.2 bars, and the bar itself
    for d in (25.4, 22.2):
        comp, _ = new_part(root, 'Bar shim %.1f' % d, m)
        s = xcyl(comp, by, 0.0, BORE_R - 0.15, *RING_X, name='Bar shim %.1f' % d)
        xcyl(comp, by, 0.0, d/2, RING_X[0] - 1, RING_X[1] + 1, CUT, bodies=[s])
        xpoly(comp, [(by - BORE_R, -1.0), (by - d/2 + 0.5, -1.0), (by - d/2 + 0.5, 1.0), (by - BORE_R, 1.0)],
              RING_X[0] - 1, RING_X[1] + 1, CUT, bodies=[s])
    find_occ(root, 'Bar shim 25.4').isLightBulbOn = False
    comp, _ = new_part(root, 'REF - Handlebar 22.2', m)
    xcyl(comp, by, 0.0, 11.1, -60.0, 76.0, name='handlebar')
    out['mount'] = 'ok'


# ---------------------------------------------------------------- look, check, export

PRINTED = {
    'Front shell': 'ASA', 'Lid': 'ASA', 'Key': 'PETG yellow', 'Switch carrier': 'PETG',
    'Quarter-turn tab': 'ASA', 'Receiver arm': 'ASA', 'Clamp upper': 'ASA', 'Clamp lower': 'ASA',
    'Tilt knob': 'PETG yellow', 'Lens': 'acrylic 2.0 sheet (cut)', 'Lens tape (VHB)': 'VHB 4905 or any 0.5 clear tape',
    'Display gasket': 'TPU 95A', 'Lid gasket': 'TPU 95A', 'Key membrane': 'TPU 95A',
    'USB plug': 'TPU 95A', 'Tab gasket': 'TPU 95A', 'Bar shim 25.4': 'TPU 95A',
    'Bar shim 22.2': 'TPU 95A',
}
SEALS = ('Lid gasket', 'Key membrane', 'Tab gasket', 'USB plug', 'Bar shim')
# Parts meant to be squeezed: (a, b) -> why
PRELOAD = {
    frozenset(('Key membrane', 'Switch carrier')): 'membrane clamp, 0.1',
    frozenset(('Front shell', 'Lid gasket')): 'gasket set 0.1 + seal bead 0.3',
    frozenset(('Front shell', 'USB plug')): 'plug sealing ribs, 0.3',
}
OBSOLETE = ('Visor', 'Light pipe')


def stage_look(app, design, root, out):
    """Light, readable colours (no black anywhere) and the UI on the screen."""
    for name in OBSOLETE:
        o = find_occ(root, name)
        if o:
            o.deleteMe()
    lib = [app.materialLibraries.item(i) for i in range(app.materialLibraries.count)
           if 'Appearance' in app.materialLibraries.item(i).name][0]

    def get(name, src, rgb=None):
        ap = design.appearances.itemByName(name)
        if not ap:
            ap = design.appearances.addByCopy(lib.appearances.itemByName(src), name)
        if rgb:
            for p in ap.appearanceProperties:
                if isinstance(p, adsk.core.ColorProperty) and p.id in ('opaque_albedo', 'surface_albedo'):
                    p.value = adsk.core.Color.create(rgb[0], rgb[1], rgb[2], 255)
        return ap

    body = get('Raah grey (ASA)', 'Plastic - Matte (Gray)', (204, 202, 196))
    yellow = get('Raah yellow (PETG)', 'Plastic - Matte (Yellow)', (255, 208, 0))
    seal = get('Raah seal orange', 'Plastic - Matte (Red)', (255, 110, 40))
    lens = get('Raah lens (acrylic)', 'Acrylic (Clear)')
    tape = get('Raah VHB (opaque)', 'Plastic - Matte (Gray)', (138, 140, 144))   # translucent renders as streaks
    glass = get('REF LCD glass (matte)', 'Plastic - Matte (Gray)', (88, 96, 110))   # glossy shows the studio photo as streaks
    screen = get('REF LCD screen', 'Plastic - Matte (Gray)', (96, 100, 108))   # matte, or it mirrors the studio
    pcb_red = get('REF red PCB', 'Plastic - Matte (Red)', (190, 50, 45))
    pcb_blue = get('REF blue PCB', 'Plastic - Matte (Blue)', (70, 120, 200))
    ref = get('REF grey', 'Plastic - Matte (Gray)', (150, 152, 156))
    tpu = get('Raah TPU grey', 'Plastic - Matte (Gray)', (120, 124, 132))
    cell = get('REF cell', 'Plastic - Matte (Gray)', (120, 170, 210))
    bar = get('REF bar', 'Plastic - Matte (Gray)', (170, 172, 176))
    fpc = get('REF FPC amber', 'Plastic - Matte (Yellow)', (214, 150, 60))
    for i in range(root.occurrences.count):
        o = root.occurrences.item(i)
        n = o.component.name
        if n in ('Key', 'Tilt knob'):
            ap = yellow
        elif n == 'Lens':
            ap = lens
        elif n == 'Lens tape (VHB)':
            ap = tape
        elif n.startswith(SEALS):
            ap = seal
        elif n == 'Display gasket':
            ap = tpu
        elif n in PRINTED:
            ap = body
        elif n.startswith('REF - Handlebar'):
            ap = bar
        elif n.startswith('REF - XIAO'):
            for b in o.component.bRepBodies:
                b.appearance = pcb_blue if b.name == 'XIAO PCB' else ref
            continue
        elif n.startswith('REF - 523450') or n.startswith('REF - FPC'):
            for b in o.component.bRepBodies:
                b.appearance = (fpc if b.name.startswith('FPC') else
                                ref if b.name.startswith(('coax', 'cell leads')) else cell)
            continue
        elif n.startswith('REF - ILI9341'):
            for b in o.component.bRepBodies:
                b.appearance = (pcb_red if b.name == 'LCD PCB' else screen if b.name == 'LCD active area' else
                                glass if b.name.startswith('LCD') else ref)
            continue
        else:
            ap = ref
        o.appearance = ap
        for b in o.component.bRepBodies:
            b.appearance = ap
    screen_decal(root, out)


def screen_decal(root, out):
    """Put a firmware UI screenshot on the active area (render only)."""
    img = os.path.join(os.path.dirname(CASE_DIR), 'docs', 'screens', '11_map_cruise.png')
    # The same 240 x 320 shot blown up 4x with hard pixel edges, so close-ups stay sharp:
    #   ffmpeg -i docs/screens/11_map_cruise.png -vf scale=960:1280:flags=neighbor case/fusion/assets/screen_map_4x.png
    sharp = os.path.join(CASE_DIR, 'fusion', 'assets', 'screen_map_4x.png')
    img = sharp if os.path.exists(sharp) else img
    o = find_occ(root, 'REF - ILI9341 2.8in module')
    if o is None or not os.path.exists(img):
        return
    comp = o.component
    for i in range(comp.decals.count - 1, -1, -1):
        comp.decals.item(i).deleteMe()
    aa = comp.bRepBodies.itemByName('LCD active area')
    face = planar_face(aa, 'z', 0.0, True)
    inp = comp.decals.createInput(img, [face], face.pointOnFace)
    inp.isChainFaces = False
    k = 1.0 if not FLIP_DISPLAY else -1.0   # the firmware mirrors when flipped; the picture stays upright
    m = adsk.core.Matrix3D.create()
    m.setWithCoordinateSystem(adsk.core.Point3D.create(0, 0, 0),
                              adsk.core.Vector3D.create(2*AA_HX*MM*k, 0, 0),
                              adsk.core.Vector3D.create(0, 2*AA_HY*MM*k, 0),
                              adsk.core.Vector3D.create(0, 0, 1))
    inp.transform = m
    comp.decals.add(inp)
    out['decal'] = img


def interference(design, root, skip_ref_pairs=True):
    coll = adsk.core.ObjectCollection.create()
    for o in root.allOccurrences:
        if not o.isLightBulbOn and o.assemblyContext is None:
            continue
        if any(s in o.name for s in ('silkscreen', 'copper', 'soldermask')):
            continue
        for b in o.bRepBodies:
            coll.add(b)
    inp = design.createInterferenceInput(coll)
    inp.areCoincidentFacesIncluded = False
    res = design.analyzeInterference(inp)

    def part(e):
        o = e.assemblyContext
        while o is not None and o.assemblyContext is not None:
            o = o.assemblyContext
        return o.component.name if o else e.parentComponent.name

    clashes, preload = [], []
    for r in res:
        a, b = r.entityOne, r.entityTwo
        pa, pb = part(a), part(b)
        if pa == pb:
            continue
        vol = r.interferenceBody.volume*1000
        key = frozenset((pa if pa in PRINTED else a.name, pb if pb in PRINTED else b.name))
        line = '%s/%s <-> %s/%s : %.3f mm3' % (pa, a.name, pb, b.name, vol)
        known = lambda n: n in PRINTED or n.startswith('REF - ')
        if not known(pa) and not known(pb):
            continue                       # inside one imported model
        if key in PRELOAD:
            preload.append(line + '  [' + PRELOAD[key] + ']')
        elif pa in PRINTED or pb in PRINTED or not skip_ref_pairs:
            clashes.append(line)
        else:
            clashes.append('REF ' + line)
    return clashes, preload


def stage_check(app, design, root, out):
    global FLIP_DISPLAY
    report = []
    for flip in (False, True):
        FLIP_DISPLAY = flip
        build_display_and_gasket(root)
        clashes, preload = interference(design, root)
        report.append(('flipped' if flip else 'header down', clashes, preload))
    FLIP_DISPLAY = False
    build_display_and_gasket(root)
    for name, clashes, preload in report:
        print('== display %s: %d clashes' % (name, len(clashes)))
        for c in clashes:
            print('  ', c)
        print('   intended:', len(preload))
        for c in preload:
            print('     ', c)
    out['check'] = report


def build_display_and_gasket(root):
    """Rebuild only the display reference and its gasket (for the flip check)."""
    comp, _ = new_part(root, 'REF - ILI9341 2.8in module', flip_matrix())
    build_display_ref(comp)
    build_display_gasket(root)


SHEET = ('Lens', 'Lens tape (VHB)')   # cut from sheet, not printed


def stage_export(app, design, root, out):
    """STL for printed parts, DXF outlines (cutting templates) for the lens and tape, one assembly STEP."""
    os.makedirs(EXPORT_DIR, exist_ok=True)
    em = design.exportManager
    done = []
    slug = lambda n: n.lower().replace(' (vhb)', '_vhb').replace(' ', '_').replace('.', '')
    for name in PRINTED:
        o = find_occ(root, name)
        if o is None:
            continue
        if name in SHEET:
            body = o.component.bRepBodies.item(0)
            top = max((f for f in body.faces if isinstance(f.geometry, adsk.core.Plane)
                       and abs(f.evaluator.getNormalAtPoint(f.pointOnFace)[1].z) > 0.99), key=lambda f: f.area)
            sk = o.component.sketches.add(top)
            sk.name = 'dxf'
            for lp in top.loops:
                for e in lp.edges:
                    sk.project(e)
            path = os.path.join(EXPORT_DIR, 'raah_%s.dxf' % slug(name))
            sk.saveAsDXF(path)
            sk.deleteMe()
        else:
            path = os.path.join(EXPORT_DIR, 'raah_%s.stl' % slug(name))
            opt = em.createSTLExportOptions(o, path)
            opt.meshRefinement = adsk.fusion.MeshRefinementSettings.MeshRefinementHigh
            opt.isOneFilePerBody = False
            em.execute(opt)
        done.append(os.path.basename(path))
    em.execute(em.createSTEPExportOptions(os.path.join(EXPORT_DIR, 'raah_assembly.step'), root))
    done.append('raah_assembly.step')
    out['exported'] = done


# ---------------------------------------------------------------- renders

RENDER_DIR = os.path.join(CASE_DIR, 'renders')
DEVICE = ('Front shell', 'Lid', 'Key', 'Lens', 'USB plug', 'Quarter-turn tab', 'Tab gasket', 'Lid gasket',
          'Key membrane', 'Switch carrier', 'Display gasket', 'Lens tape', 'REF - XIAO', 'REF - ILI9341',
          'REF - 523450', 'REF - FPC', 'REF - Key switches')
MOUNT = ('Receiver arm', 'Clamp upper', 'Clamp lower', 'Tilt knob', 'Bar shim 22.2', 'REF - Handlebar')
EXPLODE = {  # mm along the device Z (face out = +)
    'Lens': 46, 'Lens tape (VHB)': 36, 'Key': 28, 'Front shell': 0, 'Display gasket': -16,
    'REF - ILI9341 2.8in module': -26, 'Key membrane': -14, 'Switch carrier': -24,
    'REF - Key switches and LDR': -34, 'Lid gasket': -48, 'Lid': -64, 'REF - XIAO ESP32C3': -64,
    'REF - 523450 LiPo 1500mAh': -64, 'REF - FPC antenna': -64,
    'Tab gasket': -78, 'Quarter-turn tab': -88, 'USB plug': -64,
}


def world_dirs():
    t = math.radians(TILT_DEG)
    up = (0.0, math.cos(t), math.sin(t))         # world up, in the device frame
    rider = (0.0, -math.sin(t), math.cos(t))     # level, toward the rider
    return up, rider


def snap(name, w=1600, h=1200):
    os.makedirs(RENDER_DIR, exist_ok=True)
    vp = adsk.core.Application.get().activeViewport
    path = os.path.join(RENDER_DIR, name + '.png')
    try:
        opt = adsk.core.SaveImageFileOptions.create(path)
        opt.width, opt.height = w, h
        opt.isAntiAliased = True
        opt.isBackgroundTransparent = True
        vp.saveAsImageFileWithOptions(opt)
    except Exception:
        vp.saveAsImageFile(path, w, h)
    return path


def mix(*terms):
    return tuple(sum(k*v[i] for k, v in terms) for i in range(3))


def stage_render(app, design, root, out):
    up, rider = world_dirs()
    x = (1.0, 0.0, 0.0)
    done = []
    show_only(root, DEVICE + MOUNT)
    view(mix((0.85, rider), (0.75, up), (0.55, x)), up=up, target=(0, -14, -21), dist=560, fit=False)
    done.append(snap('01_hero_mounted'))
    view(mix((1.0, (0, 0, 1)),), up=(0, 1, 0), target=(0, -7, 0), dist=490, fit=False)
    done.append(snap('02_face'))
    view(mix((-1.0, x),), up=up, target=(0, -18, -31), dist=520, fit=False)
    done.append(snap('03_side_tilt'))
    view(mix((-0.8, rider), (0.45, up), (-0.6, x)), up=up, target=(0, -16, -31), dist=560, fit=False)
    done.append(snap('04_back_mount'))
    show_only(root, DEVICE)
    view(mix((0.35, x), (-0.55, (0, 1, 0)), (-1.0, (0, 0, 1))), up=(0, 1, 0), target=(0, -7, -10), dist=470,
         fit=False)
    done.append(snap('05_back_tab'))
    show_only(root, DEVICE, hide=('Front shell', 'Lens', 'Key', 'Display gasket', 'REF - ILI9341',
                                  'USB plug', 'Lid gasket', 'Switch carrier', 'REF - Key switches'))
    view(mix((0.3, x), (-0.35, (0, 1, 0)), (1.0, (0, 0, 1))), up=(0, 1, 0), target=(0, -7, -13), dist=470,
         fit=False)
    done.append(snap('06_inside_lid'))
    show_only(root, ('Front shell', 'Key', 'Key membrane', 'Switch carrier', 'REF - Key switches'))
    view(mix((-0.3, x), (-0.35, (0, 1, 0)), (-1.0, (0, 0, 1))), up=(0, 1, 0), target=(0, -7, -6), dist=470,
         fit=False)
    done.append(snap('07_inside_shell'))
    # exploded: move, shoot, put back (no features in between, so it sticks)
    show_only(root, tuple(EXPLODE))
    saved = {}
    for name, dz in EXPLODE.items():
        o = find_occ(root, name)
        saved[name] = o.transform2.copy()
        m = o.transform2.copy()
        tr = m.translation
        tr.z = tr.z + dz*MM
        if name == 'USB plug':
            tr.y = tr.y - 18*MM
        m.translation = tr
        o.transform2 = m
    view(mix((0.9, x), (0.25, (0, 1, 0)), (0.35, (0, 0, 1))), up=(0, 1, 0), target=(0, -8, -19), dist=620,
         fit=False)
    done.append(snap('08_exploded'))
    for name, m in saved.items():
        find_occ(root, name).transform2 = m
    show_only(root, None, hide=('Bar shim 25.4',))
    view(mix((0.85, rider), (0.75, up), (0.55, x)), up=up, target=(0, -14, -21), dist=560, fit=False)
    out['renders'] = [os.path.basename(d) for d in done]
