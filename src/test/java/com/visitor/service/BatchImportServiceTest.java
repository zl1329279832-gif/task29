package com.visitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visitor.common.exception.BusinessException;
import com.visitor.dto.response.BatchImportResultResponse;
import com.visitor.entity.BatchImportRecord;
import com.visitor.mapper.AppointmentMapper;
import com.visitor.mapper.BatchImportRecordMapper;
import com.visitor.mapper.VisitorMapper;
import com.visitor.security.CustomUserDetails;
import com.visitor.service.impl.BatchImportServiceImpl;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BatchImportServiceTest {

    @Mock private BatchImportRecordMapper batchImportRecordMapper;
    @Mock private VisitorMapper visitorMapper;
    @Mock private AppointmentMapper appointmentMapper;
    @Mock private BlacklistService blacklistService;

    @InjectMocks
    private BatchImportServiceImpl batchImportService;

    @BeforeEach
    void setUp() throws Exception {
        // Inject ObjectMapper using reflection
        java.lang.reflect.Field field = BatchImportServiceImpl.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(batchImportService, new ObjectMapper());

        CustomUserDetails userDetails = new CustomUserDetails(
                1L, "employee1", "password", "Employee", "IT",
                List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
    }

    @Test
    void importVisitors_allSuccess() throws IOException {
        MockMultipartFile file = createExcelFile(List.of(
                new String[]{"张三", "13800000001", "110101200001011234", "ABC公司", "商务洽谈", "2025-12-01 10:00:00", "2025-12-01 12:00:00"},
                new String[]{"李四", "13800000002", "", "XYZ公司", "技术交流", "2025-12-01 14:00:00", "2025-12-01 16:00:00"}
        ));

        when(blacklistService.isBlacklisted(anyString(), any())).thenReturn(false);
        when(visitorMapper.findByPhone(anyString())).thenReturn(null);

        BatchImportResultResponse result = batchImportService.importVisitors(file);

        assertNotNull(result);
        verify(batchImportRecordMapper).updateResult(any(), eq(2), eq(0), eq("COMPLETED"), any());
    }

    @Test
    void importVisitors_partialFail() throws IOException {
        MockMultipartFile file = createExcelFile(List.of(
                new String[]{"张三", "13800000001", "", "", "商务洽谈", "2025-12-01 10:00:00", "2025-12-01 12:00:00"},
                new String[]{"", "13800000002", "", "", "技术交流", "2025-12-01 14:00:00", "2025-12-01 16:00:00"} // missing name
        ));

        when(blacklistService.isBlacklisted(anyString(), any())).thenReturn(false);
        when(visitorMapper.findByPhone("13800000001")).thenReturn(null);

        BatchImportResultResponse result = batchImportService.importVisitors(file);

        assertNotNull(result);
        verify(batchImportRecordMapper).updateResult(any(), eq(1), eq(1), eq("PARTIAL_FAIL"), any());
    }

    @Test
    void importVisitors_blacklisted_skipped() throws IOException {
        MockMultipartFile file = createExcelFile(List.<String[]>of(
                new String[]{"黑名单用户", "13900000001", "110101200001011234", "", "访问", "2025-12-01 10:00:00", "2025-12-01 12:00:00"}
        ));

        when(blacklistService.isBlacklisted("13900000001", "110101200001011234")).thenReturn(true);

        BatchImportResultResponse result = batchImportService.importVisitors(file);

        assertNotNull(result);
        verify(batchImportRecordMapper).updateResult(any(), eq(0), eq(1), eq("FAILED"), any());
    }

    @Test
    void getImportStatus_notFound_throwsException() {
        when(batchImportRecordMapper.findById(99L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> batchImportService.getImportStatus(99L));
    }

    @Test
    void getImportStatus_success() {
        BatchImportRecord record = new BatchImportRecord();
        record.setId(1L);
        record.setFileName("test.xlsx");
        record.setTotalCount(5);
        record.setSuccessCount(3);
        record.setFailCount(2);
        record.setStatus("PARTIAL_FAIL");
        record.setErrorDetail("[{\"rowNumber\":3,\"errorMessage\":\"手机号为空\"}]");

        when(batchImportRecordMapper.findById(1L)).thenReturn(record);

        BatchImportResultResponse result = batchImportService.getImportStatus(1L);

        assertEquals(5, result.getTotalCount());
        assertEquals(3, result.getSuccessCount());
        assertEquals(2, result.getFailCount());
        assertEquals("PARTIAL_FAIL", result.getStatus());
    }

    private MockMultipartFile createExcelFile(List<String[]> rows) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Sheet1");
        Row header = sheet.createRow(0);
        String[] headers = {"访客姓名", "手机号", "身份证号", "所属公司", "来访事由", "到访开始时间", "到访结束时间"};
        for (int i = 0; i < headers.length; i++) {
            header.createCell(i).setCellValue(headers[i]);
        }
        for (int i = 0; i < rows.size(); i++) {
            Row row = sheet.createRow(i + 1);
            String[] data = rows.get(i);
            for (int j = 0; j < data.length; j++) {
                row.createCell(j).setCellValue(data[j]);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        workbook.close();
        return new MockMultipartFile("file", "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
    }
}
