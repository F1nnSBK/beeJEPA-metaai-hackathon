/**
 * Story acts in scroll order. The scroll story is a continuous value from 0 to
 * ACT.ENTER + 1: the integer part is the act, the fractional part the progress inside it.
 *
 * Kept free of three.js imports so the page text can render before the WebGL chunk loads.
 */
export const ACT = Object.freeze({
  ARRIVAL: 0,
  PERIPHERY: 1,
  SURPRISE: 2,
  SACCADE: 3,
  REPORT: 4,
  ENTER: 5
})
