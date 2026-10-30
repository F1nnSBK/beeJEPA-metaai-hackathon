import {
  Color,
  Euler,
  Group,
  InstancedMesh,
  Matrix4,
  MeshStandardMaterial,
  Object3D,
  Vector3
} from 'three'
import { PALETTE } from './palette.js'

const GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5))
const LANDING_STAGGER = 0.045
const DISSOLVE_START = 0.82
const WING_FLAP_RAD_PER_SEC = 38.0
const WING_FLAP_AMPLITUDE = 0.58
const ROAM_SCALE_BOOST = 1.45

function easeInOutCubic(x) {
  return x < 0.5 ? 4 * x * x * x : 1 - (-2 * x + 2) ** 3 / 2
}

/**
 * Boosts the golden/amber vertex colors on the low-poly Blender bee body so its
 * honey stripes read clearly against the dark scene while keeping dark chitin
 * stripes and compound eyes deep espresso-black.
 */
function enhanceBeeVertexColors(geometry) {
  const colorAttr = geometry.attributes.color
  if (!colorAttr) return
  for (let i = 0; i < colorAttr.count; i++) {
    const r = colorAttr.getX(i)
    const g = colorAttr.getY(i)
    const b = colorAttr.getZ(i)
    if (r > 0.18) {
      colorAttr.setXYZ(i, r * 1.55, g * 1.5, b * 1.35)
    }
  }
  colorAttr.needsUpdate = true
}

/**
 * Low-poly 3D honeybee swarm built in Blender (`bee.glb`: `BeeBody`, `BeeWingLeft`, `BeeWingRight`).
 * Exactly one bee per real SAM 3.1 detection inside the hotspot.
 * While roaming, the bees follow the camera's point of interest with flapping wings;
 * during the saccade, each bee lands on the centre of its SAM 3.1 bounding box.
 */
export class BeeSwarm {
  constructor(stage, beeScene) {
    this.targets = stage.focus.samBoxes.map(([x1, y1, x2, y2]) => ({
      position: stage.worldPointAt((x1 + x2) / 2, (y1 + y2) / 2).add(new Vector3(0, 0, 0.025)),
      length: (Math.max(x2 - x1, y2 - y1) / stage.focus.frame.width) * stage.width * 0.5,
      angle: x2 - x1 >= y2 - y1 ? 0 : Math.PI / 2
    }))

    const count = this.targets.length
    const bodyNode = beeScene.getObjectByName('BeeBody')
    const wingLeftNode = beeScene.getObjectByName('BeeWingLeft')
    const wingRightNode = beeScene.getObjectByName('BeeWingRight')

    this.bodyGeometry = bodyNode.geometry.clone()
    enhanceBeeVertexColors(this.bodyGeometry)
    this.wingLeftGeometry = wingLeftNode.geometry.clone()
    this.wingRightGeometry = wingRightNode.geometry.clone()

    this.wingLeftOrigin = wingLeftNode.position.clone()
    this.wingRightOrigin = wingRightNode.position.clone()
    this.wingLeftRestRot = new Euler().copy(wingLeftNode.rotation)
    this.wingRightRestRot = new Euler().copy(wingRightNode.rotation)

    this.bodyMaterial = new MeshStandardMaterial({
      color: 0xffffff,
      vertexColors: true,
      flatShading: true,
      roughness: 0.36,
      metalness: 0.06
    })

    this.wingMaterial = new MeshStandardMaterial({
      color: 0xe6f2ff,
      emissive: new Color(PALETTE.electric),
      emissiveIntensity: 0.28,
      roughness: 0.14,
      metalness: 0.1,
      transparent: true,
      opacity: 0.68,
      flatShading: true,
      depthWrite: false
    })

    this.bodyMesh = new InstancedMesh(this.bodyGeometry, this.bodyMaterial, count)
    this.wingLeftMesh = new InstancedMesh(this.wingLeftGeometry, this.wingMaterial, count)
    this.wingRightMesh = new InstancedMesh(this.wingRightGeometry, this.wingMaterial, count)

    this.bodyMesh.frustumCulled = false
    this.wingLeftMesh.frustumCulled = false
    this.wingRightMesh.frustumCulled = false

    this.mesh = new Group()
    this.mesh.add(this.bodyMesh, this.wingLeftMesh, this.wingRightMesh)

    this.dummy = new Object3D()
    this.dummy.rotation.order = 'ZXY'
    this.wingDummy = new Object3D()
    this.scratchMatrix = new Matrix4()
    this.roamCenter = new Vector3()
    this.roamRadius = 1
    this.landing = 0
    this.previous = this.targets.map(() => new Vector3())
    this.scratchRoam = new Vector3()
    this.scratchVelocity = new Vector3()
  }

  setRoam(center, radius) {
    this.roamCenter.copy(center)
    this.roamRadius = radius
  }

  setLanding(value) {
    this.landing = value
  }

  update(elapsedSec) {
    const roam = this.scratchRoam
    this.targets.forEach((target, index) => {
      const phase = index * GOLDEN_ANGLE
      const speed = 0.55 + (index % 5) * 0.09
      roam.set(
        Math.cos(elapsedSec * speed + phase),
        Math.sin(elapsedSec * speed * 1.3 + phase) * 0.55,
        Math.sin(elapsedSec * speed * 0.7 + phase * 2) * 0.6
      )
      roam.multiplyScalar(this.roamRadius).add(this.roamCenter)

      const delay = index * LANDING_STAGGER
      const local = Math.min(1, Math.max(0, (this.landing - delay) / (1 - LANDING_STAGGER * this.targets.length)))
      const landed = easeInOutCubic(local)

      this.dummy.position.lerpVectors(roam, target.position, landed)
      const velocity = this.scratchVelocity.subVectors(this.dummy.position, this.previous[index])
      const flightAngle = Math.atan2(velocity.y, velocity.x)
      const heading = flightAngle + (target.angle - flightAngle) * landed
      // Tilt dorsal back (+Y in glTF) toward the camera (+Z) so stripes & wings are visible.
      const dorsalTilt = 0.55 + 0.65 * landed
      const bankPitch = Math.sin(elapsedSec * 2.2 + phase) * 0.18 * (1 - landed)
      this.dummy.rotation.set(dorsalTilt, bankPitch, heading)

      // A landed bee dissolves into its box so the real bee in the keyframe stays visible.
      const presence = 1 - Math.min(1, Math.max(0, (landed - DISSOLVE_START) / (1 - DISSOLVE_START)))
      const roamBoost = ROAM_SCALE_BOOST - (ROAM_SCALE_BOOST - 1.0) * landed
      const size = Math.max(0.0001, target.length * roamBoost * presence)
      this.dummy.scale.setScalar(size)
      this.dummy.updateMatrix()

      this.bodyMesh.setMatrixAt(index, this.dummy.matrix)

      const flap = Math.sin(elapsedSec * WING_FLAP_RAD_PER_SEC + phase * 3.0) * WING_FLAP_AMPLITUDE * (1 - landed * 0.85)

      this.wingDummy.position.copy(this.wingLeftOrigin)
      this.wingDummy.rotation.set(
        this.wingLeftRestRot.x + flap,
        this.wingLeftRestRot.y,
        this.wingLeftRestRot.z
      )
      this.wingDummy.updateMatrix()
      this.scratchMatrix.multiplyMatrices(this.dummy.matrix, this.wingDummy.matrix)
      this.wingLeftMesh.setMatrixAt(index, this.scratchMatrix)

      this.wingDummy.position.copy(this.wingRightOrigin)
      this.wingDummy.rotation.set(
        this.wingRightRestRot.x - flap,
        this.wingRightRestRot.y,
        this.wingRightRestRot.z
      )
      this.wingDummy.updateMatrix()
      this.scratchMatrix.multiplyMatrices(this.dummy.matrix, this.wingDummy.matrix)
      this.wingRightMesh.setMatrixAt(index, this.scratchMatrix)

      this.previous[index].copy(this.dummy.position)
    })

    this.bodyMesh.instanceMatrix.needsUpdate = true
    this.wingLeftMesh.instanceMatrix.needsUpdate = true
    this.wingRightMesh.instanceMatrix.needsUpdate = true
  }

  dispose() {
    this.bodyGeometry.dispose()
    this.wingLeftGeometry.dispose()
    this.wingRightGeometry.dispose()
    this.bodyMaterial.dispose()
    this.wingMaterial.dispose()
  }
}
