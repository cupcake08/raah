"""Raah product renders, ray-traced with Fusion's local renderer, for sharing.

Every shot renders on a transparent background, lit by a studio environment;
finish.sh puts the stills on a soft backdrop. Local renders run in a
background process, one at a time, so start a shot and wait for its file:
    R = runpy.run_path(<this file>)
    R['start']('hero', out_dir)
Parts are shown, hidden or moved only for the moment the render is queued,
then put back.
"""
import math
import os
import runpy

import adsk.core
import adsk.fusion

HERE = os.path.dirname(os.path.abspath(__file__))
C = runpy.run_path(os.path.join(HERE, 'raah_case.py'))
P, DEVICE, MOUNT, EXPLODE = C['P'], C['DEVICE'], C['MOUNT'], C['EXPLODE']
MM = C['MM']

ENV = 'Soft Light'   # even light; the others mirror a dark gap across the lens
X, Y, Z = (1.0, 0.0, 0.0), (0.0, 1.0, 0.0), (0.0, 0.0, 1.0)


def unit(v):
    n = math.sqrt(sum(c*c for c in v))
    return tuple(c/n for c in v)


def nudge(target, direction, up, right, upward):
    """Move a camera target across the picture: mm to the right and up as the camera sees it."""
    d = unit(direction)
    u = unit([a - sum(x*y for x, y in zip(up, d))*b for a, b in zip(up, d)])
    r = (u[1]*d[2] - u[2]*d[1], u[2]*d[0] - u[0]*d[2], u[0]*d[1] - u[1]*d[0])   # up x direction
    return tuple(t + right*a + upward*b for t, a, b in zip(target, r, u))


def shots():
    """name -> (parts shown, view direction, target, distance, up, (w, h), exploded, lens angle).

    Lens angles and nudges were set from a low-res pass so each product fills about three
    quarters of its frame, centred."""
    up, rider = C['world_dirs']()
    mix = C['mix']
    lid_hide = ('Front shell', 'Lens', 'Key', 'Display gasket', 'REF - ILI9341', 'USB plug', 'Lid gasket',
                'Switch carrier', 'REF - Key switches', 'Key membrane', 'Lens tape')
    side, back, exploded = (mix((-1.0, X), (0.15, rider)), mix((-0.8, rider), (0.45, up), (-0.6, X)),
                            mix((0.9, X), (0.25, Y), (0.35, Z)))
    return {
        'hero': (DEVICE + MOUNT, mix((0.85, rider), (0.75, up), (0.55, X)), (0, -14, -21), 520, up, (1920, 1080),
                 False, 18.0),
        'face': (DEVICE, mix((1.0, Z), (0.12, Y), (-0.10, X)), (0, -7, 0), 470, Y, (1080, 1350), False, 7.4),
        'three_quarter': (DEVICE, mix((1.0, Z), (-0.45, Y), (0.6, X)), (0, -8, -6), 480, Y, (1080, 1080), False, 9.8),
        'side': (DEVICE + MOUNT, side, nudge((0, -18, -31), side, up, 0, 5.8), 520, up, (1920, 1080), False, 15.9),
        'back': (DEVICE + MOUNT, back, nudge((0, -16, -31), back, up, -6.2, -3.6), 540, up, (1920, 1080), False,
                 15.0),
        'inside': ([n for n in DEVICE if n not in lid_hide], mix((0.3, X), (-0.35, Y), (1.0, Z)), (0, -9, -13), 430,
                   Y, (1080, 1350), False, 7.7),
        'exploded': (tuple(EXPLODE), exploded, nudge((0, -8, -19), exploded, Y, 6.7, -1.3), 600, Y, (1920, 1080),
                     True, 14.0),
    }


def scene(design, transparent=True, env=None, angle=None):
    """Studio lighting and a see-through background. Loading another environment takes Fusion a while."""
    rm = design.renderManager
    s = rm.sceneSettings
    env = env or ENV
    if s.backgroundEnvironment.name != env:
        s.backgroundEnvironment = rm.renderEnvironments.itemByName(env)
    if angle is not None:
        s.lightAngle = math.radians(angle)
    s.isGroundDisplayed = False
    s.isDepthOfFieldEnabled = False
    r = rm.rendering
    r.isBackgroundTransparent = transparent
    return r


def camera(target, direction, dist, up, fov=18.0):
    cam = adsk.core.Application.get().activeViewport.camera
    d = unit(direction)
    cam.target = P(*target)
    cam.eye = P(*[t + dist*c for t, c in zip(target, d)])
    cam.upVector = adsk.core.Vector3D.create(*up)
    cam.cameraType = adsk.core.CameraTypes.PerspectiveCameraType
    cam.perspectiveAngle = math.radians(fov)
    return cam


def explode(root, on, saved):
    for name, dz in EXPLODE.items():
        o = C['find_occ'](root, name)
        if o is None:
            continue
        if on:
            saved[name] = o.transform2.copy()
            m = o.transform2.copy()
            t = m.translation
            t.z = t.z + dz*MM
            if name == 'USB plug':
                t.y = t.y - 18*MM
            m.translation = t
            o.transform2 = m
        elif name in saved:
            o.transform2 = saved[name]


def start(name, out_dir, quality=75, size=None, cam=None, transparent=True, env=None, angle=None, tag=''):
    """Queue one shot; returns the file it will write."""
    app = adsk.core.Application.get()
    design = adsk.fusion.Design.cast(app.activeProduct)
    root = design.rootComponent
    show, direction, target, dist, up, wh, exploded, fov = shots()[name]
    r = scene(design, transparent, env, angle)
    w, h = size or wh
    r.resolution = adsk.fusion.RenderResolutions.CustomRenderResolution
    r.aspectRatio = adsk.fusion.RenderAspectRatios.CustomRenderAspectRatio  # else it follows the viewport
    r.resolutionWidth, r.resolutionHeight = w, h
    r.renderQuality = quality
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, '%s%s.png' % (name, tag))
    if os.path.exists(path):
        os.remove(path)
    C['show_only'](root, tuple(show), hide=('Bar shim 25.4',))
    saved = {}
    if exploded:
        explode(root, True, saved)
    try:
        r.startLocalRender(path, cam or camera(target, direction, dist, up, fov))
    finally:
        explode(root, False, saved)
        C['show_only'](root, None, hide=('Bar shim 25.4',))
    return path


TURN_AXIS = (0, -7.2, -45.2)   # middle of the device and mount (bar left out), so nothing drifts


def turntable(out_dir, start, end, n=144, quality=50, size=(1280, 720), el=24.0):
    """Queue frames [start, end) of one turn of the mounted device about world up, as turn_0000.png."""
    app = adsk.core.Application.get()
    design = adsk.fusion.Design.cast(app.activeProduct)
    root = design.rootComponent
    up, rider = C['world_dirs']()
    r = scene(design)
    r.resolution = adsk.fusion.RenderResolutions.CustomRenderResolution
    r.aspectRatio = adsk.fusion.RenderAspectRatios.CustomRenderAspectRatio
    r.resolutionWidth, r.resolutionHeight = size
    r.renderQuality = quality
    os.makedirs(out_dir, exist_ok=True)
    C['show_only'](root, DEVICE + MOUNT, hide=('Bar shim 25.4',))
    try:
        for f in range(start, min(end, n)):
            az = math.atan2(0.55, 0.85) + 2*math.pi*f/n
            d = tuple(math.cos(math.radians(el))*(math.cos(az)*a + math.sin(az)*b) + math.sin(math.radians(el))*u
                      for a, b, u in zip(rider, X, up))
            r.startLocalRender(os.path.join(out_dir, 'turn_%04d.png' % f), camera(TURN_AXIS, d, 520, up, 17.0))
    finally:
        C['show_only'](root, None, hide=('Bar shim 25.4',))
