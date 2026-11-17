/**
 * 通用 CSV 导出（P1-033）
 * ----------------------------------------------------------------
 * - BOM + UTF-8，Excel 双击打开中文不乱码；
 * - 表头可配置（title），行数据按列 key 取值；
 * - 文件名统一 `xxx-20261114.csv`；
 * - 导出即真实触发浏览器下载（Blob + a.download），不是 Toast 占位。
 */
import type { CsvColumn, CsvRow, DownloadCsvOptions, DownloadCsvResult } from './types'

/** 单元格取值：空值留空、数组用「 / 」连接、对象转 JSON、其余 String */
export function csvCell(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (Array.isArray(value)) return value.map((item) => csvCell(item)).join(' / ')
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

/** 生成 CSV 正文（不含 BOM）：所有单元格强制加引号，内部双引号转义 */
export function serializeCsv(columns: CsvColumn[], rows: CsvRow[]): string {
  const quote = (value: string): string => `"${value.replace(/"/g, '""')}"`
  const header = columns.map((column) => quote(column.title)).join(',')
  const body = rows.map((row) => columns.map((column) => quote(csvCell(row[column.key]))).join(','))
  return [header, ...body].join('\r\n')
}

/** Excel 兼容：UTF-8 BOM 前缀 */
export function csvWithBom(text: string): string {
  return `\ufeff${text}`
}

/** 文件名：`欠费台账-20261114.csv` */
export function buildCsvFilename(baseName: string, date: Date = new Date()): string {
  const pad = (value: number): string => String(value).padStart(2, '0')
  const stamp = `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}`
  return `${baseName}-${stamp}.csv`
}

/**
 * 真实触发浏览器下载，并返回文件名 / 行数 / 内容（内容便于单测与留痕）。
 * 纯数据部分（serializeCsv / buildCsvFilename）不依赖 DOM，可在 node 环境单测。
 */
export function downloadCsv(options: DownloadCsvOptions): DownloadCsvResult {
  const { columns, rows } = options
  const filename = options.filename ?? buildCsvFilename(options.baseName, options.date)
  const content = csvWithBom(serializeCsv(columns, rows))

  if (typeof document !== 'undefined' && typeof URL !== 'undefined' && typeof Blob !== 'undefined') {
    const blob = new Blob([content], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = filename
    anchor.style.display = 'none'
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    // 下载已经开始，延迟回收，避免部分浏览器取消下载
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  return { filename, rowCount: rows.length, content }
}
