import { WebPlugin } from '@capacitor/core';

import type {
  PickFilesResult,
  PickFoldersResult,
  NativeFilesPlugin,
  ReadFileOptions,
  ReadFileResult,
  WriteFileOptions,
  WriteFileResult,
  ListFolderResult,
  FolderEntry,
  FileInfo,
  CopyToAppStorageOptions,
  CopyToAppStorageResult,
} from './definitions';

/**
 * Web 降级实现：浏览器无法访问系统文件系统，只能提示不支持。
 *
 * 浏览器里可以用 `<input type="file">` + File System Access API 做类似事情，
 * 但不在本插件范围内（本插件面向原生）。
 */
export class NativeFilesWeb extends WebPlugin implements NativeFilesPlugin {
  private unsupported(): never {
    throw new Error('NativeFiles is only available on native platforms (Android/iOS)');
  }

  async pickFiles(): Promise<PickFilesResult> {
    this.unsupported();
  }

  async pickFolders(): Promise<PickFoldersResult> {
    this.unsupported();
  }

  async readFile(_options: ReadFileOptions): Promise<ReadFileResult> {
    this.unsupported();
  }

  async writeFile(_options: WriteFileOptions): Promise<WriteFileResult> {
    this.unsupported();
  }

  async deleteFile(): Promise<void> {
    this.unsupported();
  }

  async listFolder(): Promise<ListFolderResult> {
    this.unsupported();
  }

  async createFolder(): Promise<FolderEntry> {
    this.unsupported();
  }

  async deleteFolder(): Promise<void> {
    this.unsupported();
  }

  async renameFolder(): Promise<FolderEntry> {
    this.unsupported();
  }

  async getInfo(): Promise<FileInfo> {
    this.unsupported();
  }

  async copyToAppStorage(_options: CopyToAppStorageOptions): Promise<CopyToAppStorageResult> {
    this.unsupported();
  }
}
