export interface MyPlugin {
    /**
     * 打个招呼。
     * @param options.name 要问候的名字，默认 "world"
     */
    hello(options: { name?: string }): Promise<{ message: string }>;
}
