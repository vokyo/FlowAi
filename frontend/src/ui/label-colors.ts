/**
 * The label palette.
 *
 * Labels used to be coloured by a free `<input type="color">`, which meant the
 * stored value was an arbitrary hex with uncontrolled lightness and chroma — it
 * could never sit in the same family as anything else on screen. A design
 * system you have to hope users cooperate with is not a system, so the picker
 * offers these and nothing else.
 *
 * Built as oklch(L 0.13 h) with hues every 45°, and a deliberate two-step
 * lightness rhythm (0.54 / 0.72) rather than one flat lightness. Flat looks
 * tidier and measures far worse: all-pairs CIEDE2000 over dichromat simulation
 * goes from protan 3.8 / deutan 1.3 to protan 9.5 / deutan 5.3 once the
 * lightness alternates. Even so, eight hues cannot be made mutually distinct
 * for every kind of colour vision — tritan still collapses Green against Blue.
 * That is acceptable here specifically because a label always renders its name
 * beside the swatch, so the colour is a mnemonic and never the only cue.
 *
 * Stored as 6-digit hex because CreateProjectLabelRequest and
 * UpdateProjectLabelRequest validate `^#[0-9A-Fa-f]{6}$`.
 */
export const LABEL_COLORS = [
  { name: 'Rose', value: '#ad4b47' },
  { name: 'Amber', value: '#d8953d' },
  { name: 'Lime', value: '#6d7600' },
  { name: 'Green', value: '#4cbd88' },
  { name: 'Teal', value: '#008292' },
  { name: 'Blue', value: '#60aaf3' },
  { name: 'Violet', value: '#755db1' },
  { name: 'Magenta', value: '#da83be' },
] as const
