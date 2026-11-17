/**
 * OpenAPI 同步类型（源：docs/openapi/openapi.yaml v0.2.0）。
 * 修改契约时先改 openapi.yaml，再同步本文件；禁止只改前端。
 */
export type ScreenKey =
  | 'overview'
  | 'owner'
  | 'progress'
  | 'copilot'
  | 'desk'
  | 'billing'
  | 'erp'
  | 'mapping'
  | 'orchestration'
  | 'trace'
  | 'knowledge'
  | 'dashboard'

export interface ScreenMeta {
  key: ScreenKey
  title: string
  route: string
  order: number
  mock: boolean
}

export interface ScreenMock {
  screenKey: ScreenKey
  title: string
  route: string
  mock: boolean
  blocks: string[]
  updatedAt: string
}

export interface ApiSuccess<T> {
  ok: true
  data: T
  traceId: string
}

export interface ApiFailure {
  ok: false
  error: string
  traceId: string
}

export type ApiResponse<T> = ApiSuccess<T> | ApiFailure

export interface Health {
  ok: boolean
  service: string
  mock: boolean
  now: string
}
