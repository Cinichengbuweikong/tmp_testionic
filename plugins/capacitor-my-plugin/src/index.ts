import { registerPlugin } from "@capacitor/core";
import type { MyPlugin as MyPluginType } from "./definitions";


const MyPlugin = registerPlugin<MyPluginType>("MyPlugin", {
    web: () => import("./web").then(m => new m.MyPluginWeb())
});


export * from "./definitions";
export { MyPlugin };
