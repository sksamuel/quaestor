@file:Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS", "OPT_IN_USAGE")

package com.sksamuel.signum.postgres

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource
import kotlin.time.Duration

/**
 * Adds metrics for lock fast path.
 */
class LockMetrics(
   ds: DataSource,
   private val relname: String,
   private val interval: Duration?,
) : MeterBinder {

   private val template = NamedParameterJdbcTemplate(ds)
   private val query = javaClass.getResourceAsStream("/fast_path_locks.sql").bufferedReader().readText()
   private val gauges = ConcurrentHashMap<Pair<String, Boolean>, AtomicLong>()

   override fun bindTo(registry: MeterRegistry) {

      fun gauge(mode: String, fastpath: Boolean): AtomicLong {
         return gauges.getOrPut(Pair(mode, fastpath)) {
            AtomicLong(0).also {
               Gauge
                  .builder("signum.postgres.locks.fastpath") { it }
                  .description("The total number of fastpath locks")
                  .tag("relname", relname)
                  .tag("fastpath", fastpath.toString())
                  .tag("mode", mode)
                  .register(registry)
            }
         }
      }

      suspend fun query() = runCatching {
         runInterruptible(Dispatchers.IO) {
            template.query(
               query,
               MapSqlParameterSource(mapOf("relname" to relname)),
            ) { rs ->
               val fastpath = rs.getBoolean("fastpath")
               val mode = rs.getString("mode")
               val count = rs.getLong("count")
               gauge(mode, fastpath).set(count)
            }
         }
      }

      if (interval == null) {
         runBlocking {
            query()
         }
      } else {
         GlobalScope.launch {
            while (isActive) {
               delay(interval)
               query()
            }
         }
      }
   }
}
