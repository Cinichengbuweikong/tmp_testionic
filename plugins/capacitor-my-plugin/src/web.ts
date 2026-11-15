import { WebPlugin } from "@capacitor/core";
import type { MyPlugin } from "./definitions";

export class MyPluginWeb extends WebPlugin implements MyPlugin {
    async hello(options: { name?: string }): Promise<{ message: string }> {
        return { 
            message: `Hello ${options.name ?? "world"} from WebPlugin!` 
        };
    }
}
