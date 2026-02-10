tags Metrics for ProcessWithoutMetrics {
  within ProcessWithoutMetrics {
    tag t1, t2, t3 with Metric {
      name="duration",
      unit="s";
    };
  }
}