<template>
  <div class="about">
    <h1>This is an about page</h1>

    <button @click="count += 1">click {{ count }} times.</button>
    <button @click="count = 0">clear</button>

    <section class="card">
      <h2>MyPlugin</h2>
      <div class="actions">
        <button @click="myPluginHello">hello</button>
      </div>
    </section>
  </div>
</template>

<script>
import { MyPlugin } from "capacitor-my-plugin";


export default {
  data() {
    return {
      count: 0,
    };
  },

  methods: {
    async myPluginHello() {
      const res = await MyPlugin.hello({ name: "jack" }); 
      console.log(`myPluginHello: ${ res.message }`);
    },
  },
};
</script>

<style>
@media (min-width: 1024px) {
  .about {
    min-height: 100vh;
    display: flex;
    align-items: center;
  }
}
</style>
