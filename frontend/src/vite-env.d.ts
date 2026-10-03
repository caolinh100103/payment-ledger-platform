/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Shown on the sign-in page of a public demo, e.g. "alice / correct horse battery staple". */
  readonly VITE_DEMO_CREDENTIALS?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
