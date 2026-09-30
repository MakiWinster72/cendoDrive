import { File, FileArchive, FileCode2, FileImage, FileSpreadsheet, FileText, FileType2, FileVideo, Folder, Music2, Presentation } from 'lucide-vue-next'
import type { DriveItem } from '../stores/drive'

/** 后端目前只返回 folder/file；优先依据文件名扩展名选择文件图标。 */
export function iconForFile(item: Pick<DriveItem, 'name' | 'kind'>) {
  if (item.kind === 'folder') return Folder

  const extension = item.name.match(/\.([^.]+)$/)?.[1]?.toLowerCase()
  if (extension && ['doc', 'docx', 'odt', 'rtf'].includes(extension)) return FileType2
  if (extension && ['ppt', 'pptx', 'odp'].includes(extension)) return Presentation
  if (extension && ['xls', 'xlsx', 'csv', 'ods'].includes(extension)) return FileSpreadsheet
  if (extension && ['txt', 'log'].includes(extension)) return FileText
  if (extension && ['md', 'markdown', 'mdx'].includes(extension)) return FileCode2
  if (extension && ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg', 'heic', 'avif'].includes(extension)) return FileImage
  if (extension && ['mp4', 'mov', 'avi', 'mkv', 'webm', 'm4v'].includes(extension)) return FileVideo
  if (extension && ['zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz', 'tgz'].includes(extension)) return FileArchive

  if (item.kind === 'image') return FileImage
  if (item.kind === 'video') return FileVideo
  if (item.kind === 'audio') return Music2
  if (item.kind === 'doc' || item.kind === 'pdf') return FileText
  return File
}
