import {
  Color,
  DirectionalLight,
  FogExp2,
  HemisphereLight,
  NoColorSpace,
  PerspectiveCamera,
  Scene,
  SRGBColorSpace,
  TextureLoader,
  Vector3,
  WebGLRenderer
} from 'three'
import { ACT } from '../story.js'
import { BeeSwarm } from './BeeSwarm.js'
import { CameraDirector } from './CameraDirector.js'
import { EventPillars } from './EventPillars.js'
import { HoneycombField } from './HoneycombField.js'
import { KeyframeStage, STAGE_HEIGHT } from './KeyframeStage.js'
import { PALETTE } from './palette.js'
import { SurpriseRibbon } from './SurpriseRibbon.js'
import { TimelineSpace } from './TimelineSpace.js'

const SCROLL_DAMPING_PER_SEC = 5.5
const STAGE_LIFT_ABOVE_PILLAR = 0.5
const STAGE_DEPTH_OFFSET = -0.8
const CAMERA_FOV = 40
const FOG_DENSITY = 0.018
const SETTLED_STORY_DELTA = 0.002

const smoothstep = (edge0, edge1, x) => {
  const t = Math.min(1, Math.max(0, (x - edge0) / (edge1 - edge0)))
  return t * t * (3 - 2 * t)
}

async function loadTexture(loader, url, colorSpace) {
  const texture = await loader.loadAsync(url)
  texture.colorSpace = colorSpace
  return texture
}

/**
 * Owns the WebGL lifecycle of the landing page: renderer, scene graph, render loop,
 * resizing and disposal. The page only feeds it a scroll story position.
 */
export class HiveScene {
  static async create({ canvas, snapshot, quality, assetBaseUrl }) {
    const loader = new TextureLoader()
    const [frameTexture, attentionTexture] = await Promise.all([
      loadTexture(loader, `${assetBaseUrl}/keyframe.webp`, SRGBColorSpace),
      loadTexture(loader, `${assetBaseUrl}/attention.png`, NoColorSpace)
    ])
    return new HiveScene({ canvas, snapshot, quality, frameTexture, attentionTexture })
  }

  constructor({ canvas, snapshot, quality, frameTexture, attentionTexture }) {
    this.canvas = canvas
    this.quality = quality
    this.textures = [frameTexture, attentionTexture]
    this.targetStory = 0
    this.story = 0
    this.running = false
    this.wantsRunning = false
    this.sleepWhenSettled = false
    this.frameRequest = null
    this.lastFrameSec = null

    this.renderer = new WebGLRenderer({ canvas, antialias: quality.antialias, powerPreference: 'default' })
    this.renderer.setClearColor(PALETTE.propolis, 1)
    this.renderer.outputColorSpace = SRGBColorSpace

    this.scene = new Scene()
    this.scene.fog = new FogExp2(PALETTE.propolis, FOG_DENSITY)
    this.scene.add(new HemisphereLight(new Color(PALETTE.wax), new Color(PALETTE.propolis), 0.55))
    const morningSun = new DirectionalLight(new Color(PALETTE.honey), 2.2)
    morningSun.position.set(-8, 4, 6)
    this.scene.add(morningSun)

    this.camera = new PerspectiveCamera(CAMERA_FOV, 1, 0.01, 100)
    this.buildWorld(snapshot, frameTexture, attentionTexture)

    this.resizeObserver = new ResizeObserver(() => this.resize())
    this.resizeObserver.observe(canvas)
    this.handleVisibility = () => (document.hidden ? this.suspendLoop() : this.resumeLoop())
    document.addEventListener('visibilitychange', this.handleVisibility)
    this.resize()
  }

  buildWorld(snapshot, frameTexture, attentionTexture) {
    const space = new TimelineSpace(snapshot.source, snapshot.timeline)
    const focusEvent = snapshot.events.find((event) => event.id === snapshot.focus.eventId)

    this.honeycomb = new HoneycombField(space, snapshot.source.maxSurprise, this.quality.honeycombCellRadius)
    this.ribbon = new SurpriseRibbon(space, snapshot.events)
    this.pillars = new EventPillars(space, snapshot.events, focusEvent.id)
    this.stage = new KeyframeStage({
      frameTexture,
      attentionTexture,
      focus: snapshot.focus,
      position: new Vector3(
        space.xAt(focusEvent.t),
        space.heightOf(focusEvent.s) + STAGE_LIFT_ABOVE_PILLAR + STAGE_HEIGHT / 2,
        STAGE_DEPTH_OFFSET
      )
    })
    this.swarm = new BeeSwarm(this.stage)
    this.director = new CameraDirector({
      camera: this.camera,
      space,
      stage: this.stage,
      focusEvent,
      reducedMotion: this.quality.reducedMotion
    })

    this.scene.add(this.honeycomb.mesh, this.ribbon.group, this.pillars.mesh, this.stage.group, this.swarm.mesh)
  }

  /** Continuous story position: integer part is the act, fractional part the progress inside it. */
  setStory(position) {
    this.targetStory = this.quality.reducedMotion ? ACT.REPORT : position
    if (this.quality.reducedMotion) {
      this.renderFrame(performance.now() / 1000)
    }
  }

  setPointer(normalizedX, normalizedY) {
    if (this.quality.pointerParallax) {
      this.director.setPointer(normalizedX, normalizedY)
    }
  }

  start() {
    this.wantsRunning = true
    this.sleepWhenSettled = false
    this.resumeLoop()
  }

  pause() {
    this.wantsRunning = false
    this.suspendLoop()
  }

  /** Keeps rendering until the camera has caught up with the story, then stops to save battery. */
  pauseWhenSettled() {
    this.sleepWhenSettled = true
  }

  resumeLoop() {
    if (this.running || !this.wantsRunning || document.hidden) return
    this.running = true
    this.lastFrameSec = null
    const loop = (timestampMs) => {
      if (!this.running) return
      this.renderFrame(timestampMs / 1000)
      this.frameRequest = requestAnimationFrame(loop)
    }
    this.frameRequest = requestAnimationFrame(loop)
  }

  suspendLoop() {
    this.running = false
    if (this.frameRequest !== null) {
      cancelAnimationFrame(this.frameRequest)
      this.frameRequest = null
    }
  }

  renderFrame(nowSec) {
    const deltaSec = this.lastFrameSec === null ? 0 : Math.min(0.1, nowSec - this.lastFrameSec)
    this.lastFrameSec = nowSec
    const damping = this.quality.reducedMotion ? 1 : 1 - Math.exp(-SCROLL_DAMPING_PER_SEC * deltaSec)
    this.story += (this.targetStory - this.story) * (deltaSec === 0 ? 1 : damping)

    this.director.update(this.story, nowSec)
    this.applyStoryToWorld(this.story, this.director.saccadeProgress)
    this.swarm.setRoam(this.director.currentTarget, this.director.currentRoamRadius)
    this.swarm.update(this.quality.reducedMotion ? 0 : nowSec)
    this.stage.update(this.quality.reducedMotion ? 0 : nowSec)
    this.renderer.render(this.scene, this.camera)

    if (this.sleepWhenSettled && Math.abs(this.targetStory - this.story) < SETTLED_STORY_DELTA) {
      this.pause()
    }
  }

  applyStoryToWorld(story, saccade) {
    this.honeycomb.setIntensity(0.45 + 0.55 * smoothstep(0, 1, story))
    this.ribbon.setReveal(smoothstep(0.05, 1.2, story))
    this.pillars.setGrowth(smoothstep(1.9, 2.7, story))
    this.stage.setOpacity(saccade)
    this.stage.setHeat(smoothstep(3.0, 3.25, story))
    this.stage.setBoxReveal(smoothstep(3.25, 3.8, story))
    this.stage.setReticle(saccade, saccade * (1 - smoothstep(4.2, 4.6, story) * 0.65))
    this.swarm.setLanding(smoothstep(3.3, 3.9, story))
  }

  resize() {
    const width = this.canvas.clientWidth
    const height = this.canvas.clientHeight
    if (width === 0 || height === 0) return
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, this.quality.maxPixelRatio))
    this.renderer.setSize(width, height, false)
    this.camera.aspect = width / height
    this.camera.userData.viewportWidth = width
    this.camera.userData.viewportHeight = height
    this.camera.updateProjectionMatrix()
    if (!this.running) {
      this.renderFrame(performance.now() / 1000)
    }
  }

  dispose() {
    this.pause()
    this.resizeObserver.disconnect()
    document.removeEventListener('visibilitychange', this.handleVisibility)
    for (const element of [this.honeycomb, this.ribbon, this.pillars, this.stage, this.swarm]) {
      element.dispose()
    }
    this.textures.forEach((texture) => texture.dispose())
    this.renderer.dispose()
  }
}
