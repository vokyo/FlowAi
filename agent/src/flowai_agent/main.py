from fastapi import FastAPI

from flowai_agent.models.run import RunRequest

app = FastAPI()


@app.post("/runs")
async def create_run(request: RunRequest) -> dict[str, str]:
    return {"status": "mock", "goal": request.goal}
