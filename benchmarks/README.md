# Rapido result selection benchmark (Galaxy S23, 2026-09-28)

The [dataset](rapido_s23_2026-09-28.json) contains eight searches collected
from Rapido's accessibility tree on the connected Galaxy S23. Each search
field was checked to contain the exact query. The keyboard covered most of
the list, so the dataset includes the first two visible rows and their full
accessible address strings. No ride was booked.

Each model received the spoken place and the same two rows, without web
search. The response used a strict JSON schema containing a row rank, with
zero meaning “ask the user.” Requests used `store: false`. The
[runner](benchmark_rapido_results.py) randomized request order and sent each
of the eight cases twice per model, sequentially from this Mac. The key was
read from gitignored `local.properties`; it is absent from these files.

| Model | Correct / 16 | Median end-to-end time | Unjustified automatic choices |
| --- | ---: | ---: | ---: |
| `gpt-4.1-nano` | 10 | 2.59 s | 4 |
| `gpt-4.1-mini` | 12 | **1.14 s** | 4 |
| `gpt-6-luna` | **14** | 1.46 s | **2** |

The [raw results](rapido_result_benchmark_2026-09-28.json) contain each
response, elapsed time, status, and token count. All 48 requests completed.

“MG Road” was marked ambiguous because the two visible rows name roads in
different address contexts. “Bada Ganpati” was marked ambiguous because the
rows refer to an area and a temple. For these two cases, the expected answer
was zero. That judgment determines the difference between Mini and Luna:
Mini and Luna were correct on all 12 runs across the six clearer cases. Luna abstained
for MG Road twice; Mini selected the first row twice. All three models selected
the first Bada Ganpati row twice. Nano also varied between repeated answers
on several cases.

This small dataset is directional. It contains only visible rows from one
phone, and the ambiguous labels require human judgment. It cannot establish
that any model will safely choose every Indore place or reject places outside
the city. The benchmark's labels treat MG Road and Bada Ganpati as ambiguous.
The current app follows the requested simpler rule: it selects a single exact
Indore title and asks for a manual tap when multiple exact titles appear or
none can be verified. Its decision for these cases may therefore differ from
the benchmark's expected answer. No model is used for result selection.

Run again with:

```sh
python3 benchmarks/benchmark_rapido_results.py --repeats 2
```
