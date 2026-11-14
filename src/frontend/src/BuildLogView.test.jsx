import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { BuildLogPanel, copyLog, logFileName } from "./BuildLogView";

function response(text, size) {
  return {
    headers: { get: (name) => (name === "X-Log-Size" ? String(size) : null) },
    body: new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(text));
        controller.close();
      },
    }),
  };
}

function deferred() {
  let resolve;
  const promise = new Promise((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe("BuildLogPanel", () => {
  it("ignores a superseded load and resumes from the accepted offset", async () => {
    const requests = [];
    const api = vi.fn((url) => {
      const request = deferred();
      requests.push({ url, ...request });
      return request.promise;
    });
    const logPath = "/api/projects/1/builds/7/log";
    const { container, rerender } = render(
      <BuildLogPanel
        api={api}
        logPath={logPath}
        build={{ id: 7, status: "QUEUED" }}
        showMeta={false}
      />,
    );

    await waitFor(() => expect(requests).toHaveLength(1));

    // A build status change starts a second load while the first is still pending.
    rerender(
      <BuildLogPanel
        api={api}
        logPath={logPath}
        build={{ id: 7, status: "RUNNING" }}
        showMeta={false}
      />,
    );
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests[0].url).toContain("offset=0");
    expect(requests[1].url).toContain("offset=0");

    // The later request wins, so its bytes are shown and the offset moves to 3.
    requests[1].resolve(response("abc", 3));
    await waitFor(() =>
      expect(container.querySelector("pre").textContent).toBe("abc"),
    );

    // The earlier response arrives late and must not append its bytes or move the offset again.
    requests[0].resolve(response("abc", 3));
    expect(container.querySelector("pre").textContent).toBe("abc");

    // The next refresh asks for the accepted offset, not 6.
    const refresh = await screen.findByRole("button", { name: /refresh/i });
    await waitFor(() => expect(refresh.disabled).toBe(false));
    fireEvent.click(refresh);
    await waitFor(() => expect(requests).toHaveLength(3));
    expect(requests[2].url).toContain("offset=3");

    requests[2].resolve(response("", 3));
    await waitFor(() =>
      expect(container.querySelector("pre").textContent).toBe("abc"),
    );
  });

  it("retries a rate-limited log page from the same offset", async () => {
    const urls = [];
    const replies = [
      Object.assign(new Error("Too many requests. Try again later."), {
        status: 429,
        retryAfter: 2,
      }),
      response("abc", 3),
      response("", 3),
    ];
    const api = vi.fn((url) => {
      urls.push(url);
      const next = replies.shift();
      return next instanceof Error
        ? Promise.reject(next)
        : Promise.resolve(next);
    });
    const chunks = [];
    const waits = [];

    await copyLog(
      api,
      "/log",
      {
        write: async (chunk) => {
          chunks.push(chunk);
        },
      },
      async (millis) => {
        waits.push(millis);
      },
    );

    expect(urls).toEqual(["/log?offset=0", "/log?offset=0", "/log?offset=3"]);
    expect(
      new TextDecoder().decode(
        new Uint8Array(chunks.flatMap((chunk) => [...chunk])),
      ),
    ).toBe("abc");
    expect(waits).toEqual([2000]);
  });

  it("resets the retry budget after a successful page", async () => {
    const rateLimited = () =>
      Object.assign(new Error("Too many requests. Try again later."), {
        status: 429,
        retryAfter: 0,
      });
    const urls = [];
    // Six 429s are the per-page budget; a successful page must restore it, so a second run of six
    // still completes instead of aborting with the cumulative count.
    const replies = [
      ...Array.from({ length: 6 }, rateLimited),
      response("abc", 3),
      ...Array.from({ length: 6 }, rateLimited),
      response("def", 6),
      response("", 6),
    ];
    const api = vi.fn((url) => {
      urls.push(url);
      const next = replies.shift();
      return next instanceof Error
        ? Promise.reject(next)
        : Promise.resolve(next);
    });
    const chunks = [];

    await copyLog(
      api,
      "/log",
      {
        write: async (chunk) => {
          chunks.push(chunk);
        },
      },
      async () => {},
    );

    expect(urls).toEqual([
      ...Array(7).fill("/log?offset=0"),
      ...Array(7).fill("/log?offset=3"),
      "/log?offset=6",
    ]);
    expect(
      new TextDecoder().decode(
        new Uint8Array(chunks.flatMap((chunk) => [...chunk])),
      ),
    ).toBe("abcdef");
  });

  it("does not retry a download that failed for another reason", async () => {
    const api = vi.fn(() =>
      Promise.reject(Object.assign(new Error("Server error"), { status: 500 })),
    );
    await expect(
      copyLog(api, "/log", { write: async () => {} }, async () => {}),
    ).rejects.toThrow("Server error");
    expect(api).toHaveBeenCalledTimes(1);
  });
});

describe("logFileName", () => {
  it("names the log after the project, branch and build", () => {
    expect(logFileName("cisys", { id: 1, branch: "main" })).toBe(
      "cisys-main-1.log",
    );
  });

  it("makes a branch with a slash safe and leaves out missing parts", () => {
    expect(logFileName("cisys", { id: 7, branch: "feature/x" })).toBe(
      "cisys-feature_x-7.log",
    );
    expect(logFileName(undefined, { id: 3 })).toBe("3.log");
  });
});
