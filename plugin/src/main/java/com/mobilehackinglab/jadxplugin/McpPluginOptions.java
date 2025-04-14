package com.mobilehackinglab.jadxplugin;

import jadx.api.plugins.options.OptionFlag;
import jadx.api.plugins.options.impl.BasePluginOptionsBuilder;

import static com.mobilehackinglab.jadxplugin.McpPlugin.PLUGIN_ID;

public class McpPluginOptions extends BasePluginOptionsBuilder {

    private String httpInterface;

    @Override
    public void registerOptions() {
        strOption(PLUGIN_ID + ".http-interface")
                .description("interface to run mcp server on")
                .defaultValue("http://localhost:8085")
                .flags(OptionFlag.PER_PROJECT)
                .setter(v -> httpInterface = v);
    }

    public String getHttpInterface() {
        return httpInterface;
    }

}
