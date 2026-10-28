import {
  BufferGeometry,
  Color,
  Float32BufferAttribute,
  Group,
  LineBasicMaterial,
  LineSegments,
  Mesh,
  MeshBasicMaterial,
  PlaneGeometry,
  RingGeometry,
  ShaderMaterial,
  Vector3
} from 'three'
import { PALETTE } from './palette.js'

/** Fovea radius in camera-panel pixels, identical to ActivePerceptionService.DEFAULT_FOVEA_RADIUS. */
const FOVEA_RADIUS_PX = 60
export const STAGE_HEIGHT = 4.6
const OVERLAY_OFFSET = 0.006

const vertexShader = /* glsl */ `
  varying vec2 vUv;
  void main() {
    vUv = uv;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  }
`

const fragmentShader = /* glsl */ `
  uniform sampler2D uFrame;
  uniform sampler2D uAttention;
  uniform float uOpacity;
  uniform float uHeat;
  uniform float uTime;
  uniform vec3 uHeatColor;
  varying vec2 vUv;
  void main() {
    vec3 color = texture2D(uFrame, vUv).rgb;
    float attention = texture2D(uAttention, vUv).r;
    float pulse = 0.82 + 0.18 * sin(uTime * 2.4);
    color = mix(color, uHeatColor, attention * uHeat * 0.72 * pulse);
    float vignette = smoothstep(0.95, 0.35, length(vUv - 0.5));
    color *= mix(0.5, 1.0, vignette);
    vec2 edgeDistance = min(vUv, 1.0 - vUv);
    float edgeFade = smoothstep(0.0, 0.07, edgeDistance.x) * smoothstep(0.0, 0.05, edgeDistance.y);
    gl_FragColor = vec4(color, uOpacity * edgeFade);
    #include <colorspace_fragment>
  }
`

/**
 * The real keyframe of the strongest event, floating above its pillar.
 * Layers: camera panel, V-JEPA attention map, SAM 3.1 detections and the foveal reticle.
 */
export class KeyframeStage {
  constructor({ frameTexture, attentionTexture, focus, position }) {
    this.focus = focus
    this.height = STAGE_HEIGHT
    this.width = STAGE_HEIGHT * (focus.frame.width / focus.frame.height)
    this.group = new Group()
    this.group.position.copy(position)

    this.planeGeometry = new PlaneGeometry(this.width, this.height)
    this.planeMaterial = new ShaderMaterial({
      vertexShader,
      fragmentShader,
      transparent: true,
      uniforms: {
        uFrame: { value: frameTexture },
        uAttention: { value: attentionTexture },
        uOpacity: { value: 0 },
        uHeat: { value: 0 },
        uTime: { value: 0 },
        uHeatColor: { value: new Color(PALETTE.vermilion) }
      }
    })
    this.group.add(new Mesh(this.planeGeometry, this.planeMaterial))

    this.boardBoxes = this.createBoxLines(focus.boardBoxes || [], PALETTE.waxMuted)
    this.group.add(this.boardBoxes.lines)

    this.boxes = this.createBoxLines(focus.samBoxes, PALETTE.honey)
    this.group.add(this.boxes.lines)

    this.reticle = this.createReticle()
    this.reticle.position.copy(this.localPointAt(...focus.attention.peak, OVERLAY_OFFSET * 2))
    this.group.add(this.reticle)
  }

  localPointAt(px, py, z = 0) {
    const { width, height } = this.focus.frame
    return new Vector3((px / width - 0.5) * this.width, (0.5 - py / height) * this.height, z)
  }

  worldPointAt(px, py) {
    return this.localPointAt(px, py).add(this.group.position)
  }

  createBoxLines(boxList, colorHex) {
    const vertices = []
    for (const [x1, y1, x2, y2] of boxList) {
      const corners = [
        this.localPointAt(x1, y1, OVERLAY_OFFSET),
        this.localPointAt(x2, y1, OVERLAY_OFFSET),
        this.localPointAt(x2, y2, OVERLAY_OFFSET),
        this.localPointAt(x1, y2, OVERLAY_OFFSET)
      ]
      corners.forEach((corner, index) => {
        const next = corners[(index + 1) % corners.length]
        vertices.push(corner.x, corner.y, corner.z, next.x, next.y, next.z)
      })
    }
    const geometry = new BufferGeometry()
    geometry.setAttribute('position', new Float32BufferAttribute(vertices, 3))
    const material = new LineBasicMaterial({ color: colorHex, transparent: true, depthWrite: false })
    const lines = new LineSegments(geometry, material)
    lines.renderOrder = 2
    return { geometry, material, lines, count: boxList.length }
  }

  createReticle() {
    const radius = (FOVEA_RADIUS_PX / this.focus.frame.width) * this.width
    this.reticleMaterial = new MeshBasicMaterial({ color: PALETTE.vermilion, transparent: true, depthWrite: false })
    this.ringGeometry = new RingGeometry(radius * 0.94, radius, 64)

    const tick = radius * 0.45
    this.tickGeometry = new BufferGeometry()
    this.tickGeometry.setAttribute(
      'position',
      new Float32BufferAttribute(
        [
          radius, 0, 0, radius + tick, 0, 0,
          -radius, 0, 0, -radius - tick, 0, 0,
          0, radius, 0, 0, radius + tick, 0,
          0, -radius, 0, 0, -radius - tick, 0
        ],
        3
      )
    )
    this.tickMaterial = new LineBasicMaterial({ color: PALETTE.vermilion, transparent: true, depthWrite: false })

    const reticle = new Group()
    reticle.add(new Mesh(this.ringGeometry, this.reticleMaterial))
    reticle.add(new LineSegments(this.tickGeometry, this.tickMaterial))
    reticle.renderOrder = 3
    return reticle
  }

  setOpacity(value) {
    this.planeMaterial.uniforms.uOpacity.value = value
    this.group.visible = value > 0.001
  }

  setHeat(value) {
    this.planeMaterial.uniforms.uHeat.value = value
  }

  /** Reveals the real SAM 3.1 boxes one by one. */
  setBoxReveal(value) {
    const visibleHotspot = Math.round(value * this.boxes.count)
    this.boxes.geometry.setDrawRange(0, visibleHotspot * 8)
    this.boxes.material.opacity = Math.min(1, value * 3)

    const visibleBoard = Math.round(value * this.boardBoxes.count)
    this.boardBoxes.geometry.setDrawRange(0, visibleBoard * 8)
    this.boardBoxes.material.opacity = Math.min(0.42, value * 1.4)
  }

  /** lock = 0: reticle wide and loose, lock = 1: snapped onto the attention peak. */
  setReticle(lock, opacity) {
    const scale = 1 + (1 - lock) * 2.4
    this.reticle.scale.setScalar(scale)
    this.reticle.rotation.z = (1 - lock) * 0.9
    this.reticleMaterial.opacity = opacity
    this.tickMaterial.opacity = opacity
  }

  update(elapsedSec) {
    this.planeMaterial.uniforms.uTime.value = elapsedSec
  }

  dispose() {
    for (const disposable of [
      this.planeGeometry,
      this.planeMaterial,
      this.boxes.geometry,
      this.boxes.material,
      this.boardBoxes.geometry,
      this.boardBoxes.material,
      this.ringGeometry,
      this.reticleMaterial,
      this.tickGeometry,
      this.tickMaterial
    ]) {
      disposable.dispose()
    }
  }
}
