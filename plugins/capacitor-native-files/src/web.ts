import { WebPlugin } from '@capacitor/core';

import type {
  AllFilesAccessStatus,
  AppDirectoriesResult,
  DeleteResult,
  ListFolderOptions,
  ListFolderResult,
  NativeFilesPlugin,
  PathInfo,
  PickFilesOptions,
  PickFilesResult,
  PickFoldersOptions,
  PickFoldersResult,
  ReadFileOptions,
  ReadFileResult,
  WriteFileOptions,
  WriteFileResult,
} from './definitions';

/**
 * Web 降级实现：浏览器无法访问系统文件系统，只能提示不支持。
 *
 * 浏览器里可以用 `<input type="file">` + File System Access API 做类似事情，
 * 但不在本插件范围内（本插件面向原生绝对路径）。
 */
export class NativeFilesWeb extends WebPlugin implements NativeFilesPlugin {
  private unsupported(): never {
    throw new Error('NativeFiles is only available on native platforms (Android/iOS)');
  }

  async pickFiles(_options?: PickFilesOptions): Promise<PickFilesResult> {
    this.unsupported();
  }

  async pickFolders(_options?: PickFoldersOptions): Promise<PickFoldersResult> {
    this.unsupported();
  }

  async readFile(_options: ReadFileOptions): Promise<ReadFileResult> {
    this.unsupported();
  }

  async writeFile(_options: WriteFileOptions): Promise<WriteFileResult> {
    this.unsupported();
  }

  async deleteFile(_options: { path: string }): Promise<DeleteResult> {
    this.unsupported();
  }

  async listFolder(_options: ListFolderOptions): Promise<ListFolderResult> {
    this.unsupported();
  }

  async createFolder(_options: { path: string }): Promise<{ path: string; created: boolean }> {
    this.unsupported();
  }

  async deleteFolder(_options: { path: string; recursive?: boolean }): Promise<DeleteResult> {
    this.unsupported();
  }

  async renameFolder(_options: { path: string; newName: string }): Promise<{
    renamed: boolean;
    path: string;
    from: string;
  }> {
    this.unsupported();
  }

  async getInfo(_options: { path: string }): Promise<PathInfo> {
    this.unsupported();
  }

  async checkAllFilesAccess(): Promise<AllFilesAccessStatus> {
    return { granted: false, applicable: false, message: 'web platform has no filesystem access' };
  }

  async requestAllFilesAccess(): Promise<AllFilesAccessStatus> {
    return { granted: false, applicable: false, message: 'web platform has no filesystem access' };
  }

  async getAppDirectories(): Promise<AppDirectoriesResult> {
    return { platform: 'web', dirs: {} };
  }

  async copyToAppStorage(_options: { path: string; relativePath?: string }): Promise<{
    absolutePath: string;
    relativePath: string;
    bytes: number;
  }> {
    this.unsupported();
  }
}
