import { useEffect, useRef } from 'react'
import { EditorView, basicSetup } from 'codemirror'
import { EditorState } from '@codemirror/state'
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { python } from '@codemirror/lang-python'
import { java } from '@codemirror/lang-java'
import { javascript } from '@codemirror/lang-javascript'
import { tags as t } from '@lezer/highlight'
import type { ScenarioLanguage } from '../api'

/** Engiens' palette for code: grayscale, with weight and lightness doing the work instead of colour. */
const highlight = HighlightStyle.define([
  { tag: [t.keyword, t.controlKeyword, t.operatorKeyword, t.modifier], color: '#ffffff', fontWeight: '500' },
  { tag: [t.string, t.special(t.string), t.regexp], color: '#b5b5ad' },
  { tag: [t.comment, t.lineComment, t.blockComment], color: '#6b6b6b', fontStyle: 'italic' },
  { tag: [t.number, t.bool, t.null], color: '#d6d6d6' },
  { tag: [t.function(t.variableName), t.function(t.propertyName)], color: '#f2f2f2' },
  { tag: [t.typeName, t.className], color: '#cfcfcf' },
  { tag: [t.definition(t.variableName)], color: '#ededed' },
  { tag: [t.propertyName, t.variableName], color: '#dcdcdc' },
  { tag: [t.punctuation, t.operator], color: '#9a9a9a' },
])

const theme = EditorView.theme(
  {
    '&': { backgroundColor: '#0a0a0a', color: '#ededed', fontSize: '13px', height: '100%' },
    '.cm-scroller': { fontFamily: '"Geist Mono", ui-monospace, monospace', lineHeight: '1.6' },
    '.cm-content': { caretColor: '#ffffff', padding: '12px 0' },
    '.cm-gutters': { backgroundColor: '#0a0a0a', color: '#525252', border: 'none' },
    '.cm-activeLine, .cm-activeLineGutter': { backgroundColor: 'rgba(255,255,255,0.035)' },
    '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, ::selection': { backgroundColor: 'rgba(255,255,255,0.16) !important' },
    '.cm-cursor': { borderLeftColor: '#ffffff' },
    '&.cm-focused': { outline: 'none' },
    '.cm-matchingBracket': { backgroundColor: 'rgba(255,255,255,0.12)', outline: 'none' },
    '.cm-tooltip': { backgroundColor: '#111111', border: '1px solid #262626', color: '#ededed' },
  },
  { dark: true },
)

function language(lang: ScenarioLanguage | null) {
  switch (lang) {
    case 'PYTHON':
      return python()
    case 'JAVA':
      return java()
    case 'TYPESCRIPT':
      return javascript({ typescript: true })
    case 'JAVASCRIPT':
      return javascript()
    default:
      return []
  }
}

/**
 * A CodeMirror editor for one file. Mount it with a `key` per file: switching files remounts it, while the
 * parent keeps every file's text, so nothing is lost between files or modes.
 */
export function CodeEditor({
  value,
  onChange,
  lang,
  readOnly = false,
  label,
}: {
  value: string
  onChange: (value: string) => void
  lang: ScenarioLanguage | null
  readOnly?: boolean
  label: string
}) {
  const host = useRef<HTMLDivElement>(null)
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange

  useEffect(() => {
    const view = new EditorView({
      parent: host.current!,
      state: EditorState.create({
        doc: value,
        extensions: [
          basicSetup,
          language(lang),
          theme,
          syntaxHighlighting(highlight),
          EditorState.tabSize.of(4),
          EditorState.readOnly.of(readOnly),
          EditorView.editable.of(!readOnly),
          EditorView.contentAttributes.of({ 'aria-label': label }),
          EditorView.updateListener.of((u) => {
            if (u.docChanged) onChangeRef.current(u.state.doc.toString())
          }),
        ],
      }),
    })
    return () => view.destroy()
    // The editor owns the text while mounted; remount (key) to load another file.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return <div ref={host} className="h-full min-h-0 overflow-hidden" />
}
