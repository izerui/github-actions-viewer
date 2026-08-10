package com.github.ghactions.api

import com.github.ghactions.model.RunStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DtoTest {

    @Test
    fun `解析 runs 响应`() {
        val json = """
        {
          "workflow_runs": [
            {
              "id": 419001,
              "run_number": 419,
              "name": "Build and Push Docker Image",
              "head_branch": "main",
              "status": "in_progress",
              "conclusion": null,
              "html_url": "https://github.com/o/r/actions/runs/419001",
              "updated_at": "2026-08-10T07:30:00Z"
            }
          ]
        }
        """.trimIndent()

        val runs = parseRuns(json)

        assertEquals(1, runs.size)
        val run = runs[0]
        assertEquals(419001L, run.id)
        assertEquals(419, run.runNumber)
        assertEquals("Build and Push Docker Image", run.workflowName)
        assertEquals("main", run.branch)
        assertEquals(RunStatus.IN_PROGRESS, run.status)
        assertEquals("https://github.com/o/r/actions/runs/419001", run.htmlUrl)
    }

    @Test
    fun `缺少 id 的条目被丢弃`() {
        val json = """
        {"workflow_runs":[{"run_number":1,"name":"x","status":"completed","conclusion":"success"}]}
        """.trimIndent()

        assertTrue(parseRuns(json).isEmpty())
    }

    @Test
    fun `缺少可选字段时使用安全默认值`() {
        val json = """
        {"workflow_runs":[{"id":7,"status":"completed","conclusion":"success"}]}
        """.trimIndent()

        val run = parseRuns(json).single()
        assertEquals(7L, run.id)
        assertEquals(0, run.runNumber)
        assertEquals("(未命名工作流)", run.workflowName)
        assertEquals("", run.branch)
        assertEquals("", run.htmlUrl)
    }

    @Test
    fun `空响应与空数组都返回空列表`() {
        assertTrue(parseRuns("{}").isEmpty())
        assertTrue(parseRuns("""{"workflow_runs":[]}""").isEmpty())
    }

    @Test
    fun `解析 jobs 响应含 steps`() {
        val json = """
        {
          "jobs": [
            {
              "id": 88001,
              "name": "build-and-push",
              "status": "in_progress",
              "conclusion": null,
              "steps": [
                {"number": 1, "name": "Set up job", "status": "completed", "conclusion": "success"},
                {"number": 2, "name": "Build and push", "status": "in_progress", "conclusion": null}
              ]
            }
          ]
        }
        """.trimIndent()

        val jobs = parseJobs(json)

        assertEquals(1, jobs.size)
        val job = jobs[0]
        assertEquals(88001L, job.id)
        assertEquals("build-and-push", job.name)
        assertEquals(RunStatus.IN_PROGRESS, job.status)
        assertEquals(2, job.steps.size)
        assertEquals("Set up job", job.steps[0].name)
        assertEquals(RunStatus.SUCCESS, job.steps[0].status)
        assertEquals(RunStatus.IN_PROGRESS, job.steps[1].status)
    }

    @Test
    fun `解析 job 与 step 的起止时间`() {
        val json = """
        {
          "jobs": [
            {
              "id": 88001,
              "name": "build",
              "status": "completed",
              "conclusion": "success",
              "started_at": "2026-08-10T07:30:00Z",
              "completed_at": "2026-08-10T07:32:30Z",
              "steps": [
                {"number": 1, "name": "Checkout", "status": "completed", "conclusion": "success",
                 "started_at": "2026-08-10T07:30:05Z", "completed_at": "2026-08-10T07:30:20Z"}
              ]
            }
          ]
        }
        """.trimIndent()

        val job = parseJobs(json).single()
        assertEquals(150L, job.durationSeconds, "job 耗时应为 2 分 30 秒")
        assertEquals(15L, job.steps.single().durationSeconds, "step 耗时应为 15 秒")
    }

    @Test
    fun `缺少起止时间时耗时为 null`() {
        val json = """{"jobs":[{"id":1,"name":"j","status":"queued","steps":[{"number":1,"name":"s","status":"queued"}]}]}"""
        val job = parseJobs(json).single()
        assertNull(job.durationSeconds)
        assertNull(job.steps.single().durationSeconds)
    }

    @Test
    fun `只有开始时间而未结束时耗时为 null`() {
        val json = """
        {"jobs":[{"id":1,"name":"j","status":"in_progress","started_at":"2026-08-10T07:30:00Z"}]}
        """.trimIndent()
        assertNull(parseJobs(json).single().durationSeconds)
    }

    @Test
    fun `jobs 中缺少 steps 时为空列表`() {
        val json = """{"jobs":[{"id":1,"name":"j","status":"queued"}]}"""
        assertTrue(parseJobs(json).single().steps.isEmpty())
    }

    @Test
    fun `非法时间戳降级为纪元时间而不抛异常`() {
        val json = """
        {"workflow_runs":[{"id":1,"status":"queued","updated_at":"not-a-date"}]}
        """.trimIndent()

        assertEquals(java.time.Instant.EPOCH, parseRuns(json).single().updatedAt)
    }
}
