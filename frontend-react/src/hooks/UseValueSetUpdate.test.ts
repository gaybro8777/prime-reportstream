import { act } from "@testing-library/react";

import { useValueSetUpdate } from "./UseValueSets";
import { lookupTableServer } from "../__mocks__/LookupTableMockServer";
import { AppWrapper, renderHook } from "../utils/CustomRenderUtils";

// note that running the mutation tests below results in a warning:
// `Can't perform a React state update on an unmounted component`
// I am unable to find the root of the problem here though I imagine it has
// to do with the query provider somehow not cleaning up after itself when the tests
// complete. As this comment shows, this error is being phased out in future
// React versions. This is annoying but I don't plan to spend any more time on it. -DWS
// https://github.com/reactwg/react-18/discussions/82
describe("useValueSetUpdate", () => {
    beforeAll(() => lookupTableServer.listen());
    afterEach(() => lookupTableServer.resetHandlers());
    afterAll(() => lookupTableServer.close());

    const renderWithAppWrapper = () =>
        renderHook(() => useValueSetUpdate(), {
            wrapper: AppWrapper(),
        });

    test("returns trigger and loading indicator", () => {
        const { result } = renderWithAppWrapper();
        const { mutateAsync, isPending, error } = result.current;
        expect(isPending).toEqual(false);
        expect(mutateAsync).toBeInstanceOf(Function);
        expect(error).toBeNull();
    });

    test("mutation trigger returns expected values and tracks loading state", async () => {
        const { result } = renderWithAppWrapper();
        const { mutateAsync, isPending } = result.current;
        expect(isPending).toEqual(false);

        let saveResult;
        await act(async () => {
            const savePromise = mutateAsync({
                data: [
                    {
                        name: "a-path",
                        display: "hi over here first value",
                        code: "1",
                        version: "1",
                    },
                    {
                        name: "a-path",
                        display: "test, yes, second value",
                        code: "2",
                        version: "1",
                    },
                ],
                tableName: "any",
            });
            saveResult = await savePromise;
        });
        expect(saveResult).toEqual({
            lookupTableVersionId: 2,
            tableName: "sender_automation_value_set_row",
            tableVersion: 2,
            isActive: true,
            createdBy: "again@example.com",
            createdAt: "later",
            tableSha256Checksum: "checksum",
        });
    });
});
