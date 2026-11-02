<template>
  <div class="about">
    <h1>This is an about page</h1>

    <button @click="count += 1">click {{ count }} times.</button>
    <button @click="count = 0">clear</button>
  </div>
</template>

<script>
export default {
  data() {
    return {
      count: 0,
    };
  }
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
