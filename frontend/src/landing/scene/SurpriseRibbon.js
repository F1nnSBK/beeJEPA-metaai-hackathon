import { AdditiveBlending, CatmullRomCurve3, Color, Group, Mesh, NormalBlending, ShaderMaterial, TubeGeometry, Vector3 } from 'three'
import { PALETTE } from './palette.js'

const TUBE_SEGMENTS = 1120
const CORE_RADIUS = 0.035
const HALO_RADIUS = 0.15
const EVENT_GLOW_WIDTH = 0.22

const vertexShader = /* glsl */ `
  varying float vAlong;
  varying float vWorldX;
  void main() {
    vAlong = uv.x;
    vec4 world = modelMatrix * vec4(position, 1.0);
    vWorldX = world.x;
    gl_Position = projectionMatrix * viewMatrix * world;
  }
`

const fragmentShader = /* glsl */ `
  uniform float uReveal;
  uniform float uOpacity;
  uniform float uEventX[EVENT_COUNT];
  uniform vec3 uCalmColor;
  uniform vec3 uEventColor;
  varying float vAlong;
  varying float vWorldX;
  void main() {
    if (vAlong > uReveal) discard;
    float nearEvent = 0.0;
    for (int i = 0; i < EVENT_COUNT; i++) {
      float d = (vWorldX - uEventX[i]) / EVENT_GLOW_WIDTH;
      nearEvent = max(nearEvent, exp(-d * d));
    }
    vec3 color = mix(uCalmColor, uEventColor, nearEvent);
    float leadingEdge = smoothstep(uReveal - 0.004, uReveal, vAlong);
    gl_FragColor = vec4(color + leadingEdge * 0.6, uOpacity);
    #include <colorspace_fragment>
  }
`

function createRibbonMaterial(eventXs, opacity, additive) {
  return new ShaderMaterial({
    vertexShader,
    fragmentShader,
    defines: { EVENT_COUNT: eventXs.length, EVENT_GLOW_WIDTH: EVENT_GLOW_WIDTH.toFixed(3) },
    transparent: true,
    depthWrite: !additive,
    blending: additive ? AdditiveBlending : NormalBlending,
    uniforms: {
      uReveal: { value: 0 },
      uOpacity: { value: opacity },
      uEventX: { value: eventXs },
      uCalmColor: { value: new Color(PALETTE.honey) },
      uEventColor: { value: new Color(PALETTE.vermilion) }
    }
  })
}

/**
 * The recorded S(t) curve as a glowing ribbon. It is honey-colored everywhere
 * except around the recorded peak events, which glow vermilion.
 */
export class SurpriseRibbon {
  constructor(space, events) {
    this.group = new Group()
    const curve = new CatmullRomCurve3(
      space.timeline.map(([timeSec, surprise]) => new Vector3(space.xAt(timeSec), space.heightOf(surprise), 0))
    )
    const eventXs = events.map((event) => space.xAt(event.t))

    this.coreGeometry = new TubeGeometry(curve, TUBE_SEGMENTS, CORE_RADIUS, 6, false)
    this.haloGeometry = new TubeGeometry(curve, TUBE_SEGMENTS / 2, HALO_RADIUS, 6, false)
    this.coreMaterial = createRibbonMaterial(eventXs, 1, false)
    this.haloMaterial = createRibbonMaterial(eventXs, 0.16, true)
    this.group.add(new Mesh(this.coreGeometry, this.coreMaterial))
    this.group.add(new Mesh(this.haloGeometry, this.haloMaterial))
  }

  setReveal(value) {
    this.coreMaterial.uniforms.uReveal.value = value
    this.haloMaterial.uniforms.uReveal.value = value
  }

  dispose() {
    this.coreGeometry.dispose()
    this.haloGeometry.dispose()
    this.coreMaterial.dispose()
    this.haloMaterial.dispose()
  }
}
