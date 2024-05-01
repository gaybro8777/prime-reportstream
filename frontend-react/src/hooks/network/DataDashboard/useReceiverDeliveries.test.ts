import { waitFor } from "@testing-library/react";

import useReceiverDeliveries from "./UseReceiverDeliveries";
import { dataDashboardServer } from "../../../__mocks__/DataDashboardMockServer";
import { renderHook } from "../../../utils/CustomRenderUtils";
import { MemberType } from "../../../utils/OrganizationUtils";

const { mockSessionContentReturnValue } = await vi.importMock<
    typeof import("../../../contexts/Session/__mocks__/useSessionContext")
>("../../../contexts/Session/useSessionContext");

describe("useReceiverDeliveries", () => {
    beforeAll(() => dataDashboardServer.listen());
    afterEach(() => dataDashboardServer.resetHandlers());
    afterAll(() => dataDashboardServer.close());

    describe("with no Organization name", () => {
        beforeEach(() => {
            mockSessionContentReturnValue({
                authState: {
                    accessToken: { accessToken: "TOKEN" },
                } as any,
                activeMembership: undefined,

                user: {
                    isUserAdmin: false,
                    isUserReceiver: false,
                    isUserSender: false,
                    isUserTransceiver: false,
                } as any,
            });
        });

        test("returns undefined", async () => {
            const { result } = renderHook(() => useReceiverDeliveries());
            await waitFor(() => expect(result.current.data).toBeNull());
        });
    });

    describe("with Organization and service name", () => {
        beforeEach(() => {
            mockSessionContentReturnValue({
                authState: {
                    accessToken: { accessToken: "TOKEN" },
                } as any,
                activeMembership: {
                    memberType: MemberType.RECEIVER,
                    parsedName: "testOrg",
                    service: "testService",
                },

                user: {
                    isUserAdmin: false,
                    isUserReceiver: true,
                    isUserSender: false,
                    isUserTransceiver: false,
                } as any,
            });
        });

        test("returns receiver meta and deliveries", async () => {
            const { result } = renderHook(() =>
                useReceiverDeliveries("testService"),
            );

            await waitFor(() => expect(result.current.data).toHaveLength(1));
            expect(result.current.isLoading).toEqual(false);
        });
    });
});
