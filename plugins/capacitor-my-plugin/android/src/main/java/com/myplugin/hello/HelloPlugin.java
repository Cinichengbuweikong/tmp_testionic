package com.myplugin.hello;

import android.webkit.WebView;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;


@CapacitorPlugin(name = "MyPlugin")
public class HelloPlugin extends Plugin {

    @PluginMethod
    public void hello(PluginCall call) {
        String name = call.getString("name", "--");
        // 与 iOS/Web 实现和 autotest 的断言保持一致："Hello {name} from AndroidPlugin!"
        String res = "Hello " + name + " from AndroidPlugin!";

        JSObject ret = new JSObject(); 
        ret.put("message", res); 

        call.resolve(ret);
    }
}