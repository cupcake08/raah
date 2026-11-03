"""Raah animations, rendered from the Fusion viewport.

assembly:  the exploded parts fly in, in build order, then the closed device
           drops onto the bar mount and quarter-turns to lock.
turntable: one slow turn of the mounted device about world up.

Fusion's Animation workspace has no API, so this moves occurrences and the
camera and saves one viewport capture per frame; stitch.sh turns the frames
into MP4 and GIF. Nothing is modelled. Run in batches (one call per few dozen
frames) from a Fusion script:
    A = runpy.run_path(<this file>)
    A['render']('assembly', 0, 60, frames_dir)
Every batch puts the parts back where it found them (the first batch records
the assembled placements in frames_dir/home.json).
"""
import json
import math
import os
import runpy

import adsk.core

HERE = os.path.dirname(os.path.abspath(__file__))
C = runpy.run_path(os.path.join(HERE, 'raah_case.py'))
MM, P, EXPLODE, DEVICE, MOUNT = C['MM'], C['P'], C['EXPLODE'], C['DEVICE'], C['MOUNT']

FPS, W, H = 30, 1280, 720

# Assembly order: (part or group, extra seconds of travel)
LID_GROUP = ('Lid', 'REF - XIAO ESP32C3', 'REF - 523450 LiPo 1500mAh', 'REF - FPC antenna')
ORDER = [('Lens tape (VHB)',), ('Lens',), ('Key',), ('Key membrane',), ('Switch carrier',),
         ('REF - Key switches and LDR',), ('Display gasket',), ('REF - ILI9341 2.8in module',),
         ('Lid gasket',), LID_GROUP, ('Tab gasket',), ('Quarter-turn tab',), ('USB plug',)]
HOLD0, STEP, MOVE = 0.6, 0.38, 0.75
T_BUILT = HOLD0 + (len(ORDER) - 1)*STEP + MOVE*1.3      # the USB plug takes a two-leg path
T_MOUNT = T_BUILT + 0.4                                 # lift and turn, mount rises
T_DROP = T_MOUNT + 1.1                                  # push onto the receiver
T_TWIST = T_DROP + 0.6                                  # quarter-turn to lock
T_END = T_TWIST + 0.6 + 1.3
LIFT, MOUNT_RISE = 40.0, 90.0
N_ASSEMBLY = int(round(T_END*FPS))
N_TURN, TURN_EL = 180, 24.0


def ease(u):
    u = min(1.0, max(0.0, u))
    return u*u*(3 - 2*u)


def lerp(a, b, u):
    return tuple(x + (y - x)*u for x, y in zip(a, b))


def unit(v):
    n = math.sqrt(sum(c*c for c in v))
    return tuple(c/n for c in v)


def occs(root, prefixes):
    return [root.occurrences.item(i) for i in range(root.occurrences.count)
            if root.occurrences.item(i).component.name.startswith(tuple(prefixes))]


def load_home(root, frames_dir):
    """Assembled placement of every occurrence, recorded on the first batch."""
    path = os.path.join(frames_dir, 'home.json')
    if not os.path.exists(path):
        os.makedirs(frames_dir, exist_ok=True)
        data = {root.occurrences.item(i).component.name: list(root.occurrences.item(i).transform2.asArray())
                for i in range(root.occurrences.count)}
        json.dump(data, open(path, 'w'))
    home = {}
    for name, arr in json.load(open(path)).items():
        m = adsk.core.Matrix3D.create()
        m.setWithArray(arr)
        home[name] = m
    return home


def place(o, home, dx=0.0, dy=0.0, dz=0.0, rot=None):
    """Home placement, then an optional rotation, then a shift (mm, device frame)."""
    m = home[o.component.name].copy()
    if rot is not None:
        m.transformBy(rot)
    t = m.translation
    t.x, t.y, t.z = t.x + dx*MM, t.y + dy*MM, t.z + dz*MM
    m.translation = t
    o.transform2 = m


def camera(eye, target, up):
    vp = adsk.core.Application.get().activeViewport
    cam = vp.camera
    cam.target = P(*target)
    cam.eye = P(*eye)
    cam.upVector = adsk.core.Vector3D.create(*up)
    cam.isSmoothTransition = False
    cam.cameraType = adsk.core.CameraTypes.PerspectiveCameraType
    cam.perspectiveAngle = math.radians(18)
    vp.camera = cam
    vp.refresh()


def shot(target, direction, dist, up):
    return tuple(t + dist*c for t, c in zip(target, unit(direction))), target, up


def build_shot(u):
    """The 08_exploded still, swung about device Y toward the face and dollied in as it closes."""
    a = math.radians(10.0 - 45.0*u)
    x, y, z = 0.9, 0.25, 0.35
    d = (x*math.cos(a) + z*math.sin(a), y, -x*math.sin(a) + z*math.cos(a))
    return shot(lerp((0, -8, -19), (0, -7, -7), u), d, 480 - 50*u, (0, 1, 0))


def hero_shot():
    up, rider = C['world_dirs']()
    d = C['mix']((0.85, rider), (0.75, up), (0.55, (1.0, 0.0, 0.0)))
    return shot((0, -14, -21), d, 520, up)


def blend(a, b, u):
    return lerp(a[0], b[0], u), lerp(a[1], b[1], u), unit(lerp(a[2], b[2], u))


def assembly_frame(root, home, t):
    tab = P(C['TAB_C'][0], C['TAB_C'][1], 0.0)
    built = t >= T_BUILT
    C['show_only'](root, (DEVICE + MOUNT) if built else tuple(EXPLODE),
                   hide=('Bar shim 25.4',))
    # parts flying in
    for i, group in enumerate(ORDER):
        u = (t - HOLD0 - i*STEP)/MOVE
        for o in occs(root, group):
            n = o.component.name
            dz = EXPLODE.get(n, 0.0)
            if n == 'USB plug':                     # back to the bore's line, then up into it
                u2 = u/1.3
                place(o, home, dy=-18.0*(1 - ease(u2/0.55 - 0.45/0.55)), dz=dz*(1 - ease(u2/0.55)))
            else:
                place(o, home, dz=dz*(1 - ease(u)))
    # onto the mount: lift and turn, drop in, quarter-turn
    if built:
        lift = LIFT*(ease((t - T_MOUNT)/1.1) - ease((t - T_DROP)/0.6))
        turn = -90.0*(ease((t - T_MOUNT)/1.1) - ease((t - T_TWIST)/0.6))
        rot = adsk.core.Matrix3D.create()
        rot.setToRotation(math.radians(turn), adsk.core.Vector3D.create(0, 0, 1), tab)
        for o in occs(root, DEVICE):
            place(o, home, dz=lift, rot=rot)
        up, _ = C['world_dirs']()
        r = -MOUNT_RISE*(1 - ease((t - T_MOUNT)/1.1))
        for o in occs(root, MOUNT):
            place(o, home, dx=r*up[0], dy=r*up[1], dz=r*up[2])
    # camera: swing round to the face over the build, then over to the hero view
    cam = blend(build_shot(ease(t/T_BUILT)), hero_shot(), ease((t - T_MOUNT)/1.4))
    camera(*cam)


def turntable_frame(root, home, f):
    C['show_only'](root, DEVICE + MOUNT, hide=('Bar shim 25.4',))
    up, rider = C['world_dirs']()
    x = (1.0, 0.0, 0.0)
    az = math.atan2(0.55, 0.85) + 2*math.pi*f/N_TURN
    el = math.radians(TURN_EL)
    d = tuple(math.cos(el)*(math.cos(az)*r + math.sin(az)*xx) + math.sin(el)*u
              for r, xx, u in zip(rider, x, up))
    camera(*shot((0, -14, -21), d, 520, up))


def save(path):
    vp = adsk.core.Application.get().activeViewport
    opt = adsk.core.SaveImageFileOptions.create(path)
    opt.width, opt.height = W, H
    opt.isAntiAliased = True
    opt.isBackgroundTransparent = True
    vp.saveAsImageFileWithOptions(opt)


def restore(root, home):
    for i in range(root.occurrences.count):
        o = root.occurrences.item(i)
        if o.component.name in home:
            o.transform2 = home[o.component.name]
    C['show_only'](root, None, hide=('Bar shim 25.4',))
    camera(*hero_shot())


def render(kind, start, end, frames_dir):
    """Frames [start, end) of 'assembly' or 'turntable' as frames_dir/<kind>_0000.png."""
    app = adsk.core.Application.get()
    root = app.activeProduct.rootComponent
    home = load_home(root, frames_dir)
    total = N_ASSEMBLY if kind == 'assembly' else N_TURN
    try:
        for f in range(start, min(end, total)):
            if kind == 'assembly':
                assembly_frame(root, home, f/FPS)
            else:
                turntable_frame(root, home, f)
            save(os.path.join(frames_dir, '%s_%04d.png' % (kind, f)))
    finally:
        restore(root, home)
    return total
