import { Vector3 } from 'three'
import { ACT } from '../story.js'

const SACCADE_DURATION_SEC = 0.18
const ACT_BLEND_START = 0.72
const PORTRAIT_ASPECT_LIMIT = 0.9

/** Fraction of the viewport that is not covered by the text panel, per orientation. */
const LAYOUTS = Object.freeze({
  portrait: { usableWidth: 1, usableHeight: 0.54, offsetX: 0, offsetY: 0.23 },
  landscape: { usableWidth: 0.58, usableHeight: 1, offsetX: -0.21, offsetY: 0 }
})

/** Camera-panel pixel the final dolly flies into: the dark entrance slot under the landing board. */
const ENTRANCE_SLOT_PX = [300, 312]

const smoothstep = (edge0, edge1, x) => {
  const t = Math.min(1, Math.max(0, (x - edge0) / (edge1 - edge0)))
  return t * t * (3 - 2 * t)
}
const easeOutExpo = (x) => (x >= 1 ? 1 : 1 - 2 ** (-10 * x))

function shot(target, width, height, direction, widthWeight = 1) {
  return { target, width, height, direction: direction.clone().normalize(), widthWeight }
}

function blendShots(a, b, t) {
  return {
    target: a.target.clone().lerp(b.target, t),
    width: a.width + (b.width - a.width) * t,
    height: a.height + (b.height - a.height) * t,
    direction: a.direction.clone().lerp(b.direction, t).normalize(),
    widthWeight: a.widthWeight + (b.widthWeight - a.widthWeight) * t
  }
}

/**
 * Converts the scroll story into camera poses that frame each subject inside the
 * part of the screen not covered by text, for portrait phones and wide desktops alike.
 * The jump from SURPRISE to SACCADE is time based, like a real saccade: it never
 * depends on how fast the visitor scrolls.
 */
export class CameraDirector {
  constructor({ camera, space, stage, focusEvent, reducedMotion }) {
    this.camera = camera
    this.reducedMotion = reducedMotion
    this.shots = this.buildShots(space, stage, focusEvent)
    this.pointer = { x: 0, y: 0 }
    this.saccade = { from: null, startSec: 0, target: 0 }
    this.saccadeValue = 0
    this.lastPose = null
    this.currentTarget = new Vector3()
    this.currentRoamRadius = 1
  }

  buildShots(space, stage, focusEvent) {
    const focusX = space.xAt(focusEvent.t)
    const pillarTop = space.heightOf(focusEvent.s)
    const foveaCenter = stage.worldPointAt(...this.foveaCenterPx(stage.focus))
    const entrance = stage.worldPointAt(...ENTRANCE_SLOT_PX)
    const stageCenter = stage.group.position.clone()
    const front = new Vector3(0, 0, 1)

    return {
      [ACT.ARRIVAL]: (t) =>
        blendShots(
          shot(new Vector3(0, 1.2, 0), 20, 8, new Vector3(0, 0.8, 1), 0.5),
          shot(new Vector3(2, 1.3, 0), 18, 7.5, new Vector3(0.2, 0.65, 1), 0.5),
          t
        ),
      [ACT.PERIPHERY]: (t) => {
        const x = space.xAt(space.durationSec * (0.92 - 0.84 * t))
        return shot(new Vector3(x, 1.5, 0), 7.5, 4.6, new Vector3(0.45, 0.35, 1), 0.6)
      },
      [ACT.SURPRISE]: (t) =>
        blendShots(
          shot(new Vector3(focusX + 1.2, pillarTop * 0.55, 0), 6.5, pillarTop * 1.4, new Vector3(0.55, 0.28, 1), 0.8),
          shot(new Vector3(focusX + 0.6, pillarTop * 0.6, 0), 5.5, pillarTop * 1.35, new Vector3(0.3, 0.2, 1), 0.8),
          t
        ),
      [ACT.SACCADE]: (t) => {
        const size = stage.width * (0.7 - 0.12 * t)
        return shot(foveaCenter, size, size, front)
      },
      [ACT.REPORT]: (t) => {
        const overview = shot(stageCenter, stage.width * 1.1, stage.height * 1.06, front)
        const dive = shot(entrance, 0.04, 0.04, front)
        return blendShots(overview, dive, smoothstep(0.55, 1, t) ** 2)
      },
      [ACT.ENTER]: () => shot(entrance, 0.04, 0.04, front)
    }
  }

  /** Midpoint between the V-JEPA attention peak and the centre of the SAM 3.1 detections. */
  foveaCenterPx(focus) {
    const boxes = focus.samBoxes
    const samX = boxes.reduce((sum, [x1, , x2]) => sum + (x1 + x2) / 2, 0) / boxes.length
    const samY = boxes.reduce((sum, [, y1, , y2]) => sum + (y1 + y2) / 2, 0) / boxes.length
    const [peakX, peakY] = focus.attention.peak
    return [(samX + peakX) / 2, (samY + peakY) / 2]
  }

  shotAt(storyPosition) {
    const act = Math.min(ACT.ENTER, Math.floor(storyPosition))
    const local = storyPosition - act
    const current = this.shots[act](local)
    const crossesSaccade = act === ACT.SURPRISE
    if (act === ACT.ENTER || crossesSaccade || local < ACT_BLEND_START) {
      return current
    }
    return blendShots(current, this.shots[act + 1](0), smoothstep(ACT_BLEND_START, 1, local))
  }

  setPointer(x, y) {
    this.pointer.x = x
    this.pointer.y = y
  }

  /** Eased saccade progress: 0 before the jump, 1 once the fovea has locked on. */
  get saccadeProgress() {
    return this.saccadeValue
  }

  update(storyPosition, elapsedSec) {
    const aspect = this.camera.aspect
    const layout = aspect < PORTRAIT_ASPECT_LIMIT ? LAYOUTS.portrait : LAYOUTS.landscape
    const pose = this.poseFor(this.shotAt(storyPosition), layout)
    const saccadeTarget = storyPosition >= ACT.SACCADE ? 1 : 0

    if (saccadeTarget !== this.saccade.target) {
      this.saccade = { from: this.lastPose ?? pose, startSec: elapsedSec, target: saccadeTarget }
    }
    const linear =
      this.reducedMotion || !this.saccade.from
        ? 1
        : Math.min(1, (elapsedSec - this.saccade.startSec) / SACCADE_DURATION_SEC)
    const eased = easeOutExpo(linear)
    this.saccadeValue = this.saccade.target === 1 ? eased : 1 - eased

    const finalPose =
      linear < 1
        ? {
            position: this.saccade.from.position.clone().lerp(pose.position, eased),
            target: this.saccade.from.target.clone().lerp(pose.target, eased)
          }
        : pose

    this.applyPose(finalPose, layout)
    this.lastPose = finalPose
  }

  poseFor(currentShot, layout) {
    const tanHalf = Math.tan((this.camera.fov * Math.PI) / 360)
    const distanceForHeight = currentShot.height / 2 / (tanHalf * layout.usableHeight)
    const distanceForWidth =
      (currentShot.width * currentShot.widthWeight) / 2 / (tanHalf * this.camera.aspect * layout.usableWidth)
    const distance = Math.max(distanceForHeight, distanceForWidth)

    const position = currentShot.target.clone().addScaledVector(currentShot.direction, distance)
    if (!this.reducedMotion) {
      position.x += this.pointer.x * distance * 0.03
      position.y -= this.pointer.y * distance * 0.02
    }
    this.currentRoamRadius = currentShot.height * 0.35
    return { position, target: currentShot.target.clone() }
  }

  applyPose(pose, layout) {
    const { camera } = this
    camera.position.copy(pose.position)
    camera.lookAt(pose.target)
    this.currentTarget.copy(pose.target)

    const width = camera.userData.viewportWidth
    const height = camera.userData.viewportHeight
    camera.setViewOffset(width, height, layout.offsetX * width, layout.offsetY * height, width, height)
    const distance = pose.position.distanceTo(pose.target)
    camera.near = Math.max(0.001, distance * 0.02)
    camera.far = Math.max(80, distance * 6)
    camera.updateProjectionMatrix()
  }
}
